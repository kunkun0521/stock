# 股票概率预测引擎 REST 接口（`/api/prob`）

> 所属包：`com.li.stock.prob`。后端只读 `prob_*` 表 + 异步拉起 Python 引擎，不重写统计公式。
> 统一响应 `{code, message, data, disclaimer}`，`disclaimer` 固定带免责声明文案。
> 前端 `client.js` 只判断 `code===0` 并取 `data`，多余字段不影响现有封装。

## 通用约定

- 所有参数用字符串接收再解析（前端筛选条常传空串，直接绑定数值/日期会 400）。
- 日期 `yyyy-MM-dd`；概率与收益率均为 0~1 小数；分页 `page` 从 1 开始、`size` 上限 200/100。
- `model_version` 取该 `asof_date` 下最大的版本字符串，避免多次运行的多版本数据混在榜单里。
- 列表全部走覆盖索引；`ORDER BY` 只排所选列、不追加多余排序键（避免 MySQL filesort）。
- 总览接口 10 秒 TTL 进程内缓存，任务结束时主动失效。

## 接口清单

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/prob/overview` | — | 总览：基准日、模型版本、周期列表、股票池/预测数、平均 p_up、偏多数量、风险与可靠性分布、GARCH 收敛率、已结算/OOS 数、最近 8 条任务 |
| GET | `/api/prob/predictions` | `asof,horizon,sort,order,riskLevel,minGrade,keyword,excludeHighVol,page,size` | 概率榜单。`sort` 白名单：`p_up / exp_ret / exp_ret/vol_h / risk_level / reliability_grade / prob_drop_5 / prob_drop_10 / vol_h / cvar95 / var95 / q95 / code` |
| GET | `/api/prob/stocks/{market}/{code}` | — | 个股各周期最新预测、单股模型状态、近 60 日价格与 20 日已实现波动 |
| GET | `/api/prob/stocks/{market}/{code}/history` | `horizon,from,to` | 历史预测与结算复盘（最多 300 条） |
| GET | `/api/prob/stocks/{market}/{code}/signals` | `horizon` | 信号命中率（最新 train_end_date）+ 马尔可夫转移矩阵与诊断 |
| GET | `/api/prob/eval/metrics` | `horizon,scope,model` | 样本外指标（最新 eval_date） |
| GET | `/api/prob/eval/series` | `metric,horizon` | 指标按评估日的时间序列 |
| GET | `/api/prob/eval/calibration` | `horizon,model` | 校准曲线 10 分箱 |
| GET | `/api/prob/eval/methods` | `horizon` | 方法择优结果（含 EVT 三组） |
| GET | `/api/prob/backtests` | `page,size` | 回测批次列表 |
| GET | `/api/prob/backtests/{id}` | — | 回测详情：summary + nav + trades |
| GET | `/api/prob/runs` | `page,size` | 运行记录 + 当前在途任务数 |
| GET | `/api/prob/runs/{id}` | — | 单条任务进度（前端轮询） |
| POST | `/api/prob/runs` | body 见下 | 异步触发引擎任务 |
| GET | `/api/prob/config` | — | 当前生效 `config.yaml`（读文件，剥离注释后 JSON 解析） |
| GET | `/api/prob/features` | — | 特征注册表 `features.yaml`（公式/回看/入参来源/统计口径） |
| GET | `/api/prob/models` | — | 模型产物登记（最近 100 条） |

### POST /api/prob/runs

```json
{
  "type": "DAILY",
  "asof": "latest",
  "limitStocks": 0,
  "force": false,
  "noResume": false,
  "fast": false
}
```

`type`：`DAILY / PREPARE / TRAIN / PREDICT / WALKFORWARD / SETTLE / EVALUATE / BACKTEST`。
返回 `{runId, type, status:"RUNNING", message}`；前端轮询 `/api/prob/runs/{runId}`，
`status` 变为 `SUCCESS/FAILED` 后刷新各页。

实现要点（`ProbTaskService`）：
1. 先插入一条 `prob_run`（RUNNING）拿到 `run_id`；
2. 线程池异步执行 `py -3 -m engine.cli <command> --run-id <id> ...`，工作目录 `stock.prob.workdir`；
3. 引擎复用同一条记录写成功/失败数量、配置快照与完成信息；
4. 子进程退出后 Java 兜底修正状态并失效总览缓存；超过 60 分钟强制终止。
5. 最多同时 2 个任务；`stock.prob.enabled=false` 可关闭触发。

## 返回示例

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "asof": "2026-11-13",
    "horizon": 1,
    "modelVersion": "spe-1.0.0-1331e7cc52",
    "items": [
      {
        "code": "603596", "market": "SH", "p_up": 0.540974, "p_down": 0.459026,
        "exp_ret": 0.002481, "q05": -0.091397, "q95": 0.1,
        "vol_h": 0.078819, "prob_drop_5": 0.1711,
        "var95": 0.091397, "var99": 0.1, "cvar95": 0.098525,
        "risk_level": 4, "reliability_grade": "D",
        "vol_model": "GARCH", "flags": "", "selected_method": "p_up=M10|...",
        "components_json": { "p_m1": 0.54, "p_m2": 0.51, "...": "..." }
      }
    ],
    "total": 3071, "page": 1, "size": 20, "pages": 154
  },
  "disclaimer": "本系统输出为基于历史数据的统计估计，仅供量化研究参考，不构成任何投资建议。历史概率不代表未来表现，投资有风险。"
}
```

## 配置（`application.yaml`）

```yaml
stock:
  prob:
    enabled: true
    schedule-enabled: false          # true 时每个交易日定时触发 daily
    cron-daily: "0 30 18 * * MON-FRI"
    python: py                       # 或 .venv/Scripts/python.exe 绝对路径
    python-args: "-3"
    workdir: "D:/code/PythonProject/stock-probability-engine"
    config-path: "D:/code/PythonProject/stock-probability-engine/config.yaml"
    features-path: "D:/code/PythonProject/stock-probability-engine/features.yaml"
    source-table: stock_daily
```

`ProbSchemaInitializer` 在应用就绪时幂等执行 `resources/prob_schema.sql`，并补两个覆盖索引
（`idx_prob_pred_ov`、`idx_prob_pred_oos`）。用户只启动后端、还没跑过引擎时接口也不会 500。

## 性能（本机实测）

| 接口 | 优化前 | 优化后 |
| --- | --- | --- |
| `/overview` | 约 25 秒（两次 `GROUP BY` 回表读 `components_json` 各 12 秒） | **0.21 秒**（一条覆盖索引聚合 + 10 秒缓存） |
| `/predictions?size=50` | — | **0.48 秒** |
| 其余查询接口 | — | 数十毫秒 |

## 免责声明

> 本系统输出为基于历史数据的统计估计，仅供量化研究参考，不构成任何投资建议。历史概率不代表未来表现，投资有风险。
