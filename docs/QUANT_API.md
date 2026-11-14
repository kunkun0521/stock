# 量化策略研究与回测平台 · 接口与实现说明

版本 v1.0｜对应需求文档《A股量化策略研究与回测平台：需求与设计文档》P0 范围
后端包：`com.li.stock.quant`｜前端：`src/quant`｜接口前缀：`/api/quant`

## 1. 数据来源与口径

- 唯一数据源：`stock_daily_all` 视图（`stock_daily_00/30/60` 三张分表 UNION），**只读**。
- 固定 `adjustflag = 2`（前复权），与主键一致，避免读到重复行。
- 面板加载：按分表 `ORDER BY code, trade_date` 顺序流式扫描（`fetchSize = Integer.MIN_VALUE`），
  内存中按股票切成紧凑 `float[]`，不驻留 `ResultSet`。
- 数据质检（只报告不修改）：`high < low`、`close` 越界、`pct_chg` 与 `close/preclose-1` 偏差 > 0.5%，
  异常行占比 > 5% 或不足 60 根 K 线的股票剔除；结果写入 `quant_data_snapshot`。
- 停牌（`tradestatus=0` 或 `volume=0`）不可成交；ST 默认剔除；次新股上市后 60 个交易日不入池。
- 股票池等权指数：每日可交易非 ST 标的 `pct_chg` 均值逐日累乘，作为基准与大盘状态（vs MA60）。

## 2. REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/quant/strategies` | 策略列表：元信息 + ParamDef + 默认值 + 最近回测摘要 |
| GET | `/api/quant/strategies/{key}` | 单个策略详情 |
| GET | `/api/quant/data/overview` | 面板状态（未加载时自动触发加载）；含快照、行数、日期区间、质检摘要 |
| POST | `/api/quant/data/reload` | 作废内存面板并重新加载 |
| POST | `/api/quant/backtests` | 提交回测 {strategyKey, params, config} → {taskId} |
| GET | `/api/quant/backtests?page&size&strategyKey` | 任务列表（分页） |
| GET | `/api/quant/backtests/{id}` | 任务状态 / 进度 / 阶段 / 摘要 |
| GET | `/api/quant/backtests/{id}/metrics?split=ALL\|IS\|OOS` | 绩效指标 |
| GET | `/api/quant/backtests/{id}/equity` | 每日净值（nav/cash/持仓市值/基准/回撤/持仓数） |
| GET | `/api/quant/backtests/{id}/trades?page&size` | 交易明细（分页） |
| GET | `/api/quant/backtests/{id}/events` | 事件研究（持有期 × 拆分维度） |
| POST | `/api/quant/backtests/{id}/cancel` | 取消运行中的任务 |
| POST | `/api/quant/signals/scan` | 最新交易日选股（5 策略，同快照同参数 10 分钟缓存） |
| GET | `/api/quant/signals?date&strategyKey` | 查询已落库的历史信号 |

统一响应 `{code,message,data,timestamp}`；参数校验失败返回 400 与字段级错误信息。

## 3. 回测规则（引擎实现）

- T 日收盘出信号，T+1 成交；成交价可选开盘 / 均价（`amount/volume`）/ 收盘。
- 滑点默认 0.1%（买上浮、卖下浮）；成本：佣金 0.025%（最低 5 元，双向）、印花税 0.05%（卖出）、
  过户费 0.001%（双向）。
- T+1：买入当日不卖，价格类止损从次日起检查。
- 涨停买不进：T+1 开盘涨幅达涨停（按板块/ST 阈值，留 0.1 容差）时取消或顺延。
- 跌停卖不出：开盘即跌停且全天未打开（`open == high`）时顺延。
- 停牌不可成交，挂单顺延；跳空低于止损价按开盘价成交。
- 仓位：初始资金、最大持仓数、单票上限（按总资产）、同日候选按评分降序→成交额降序→代码升序
  取前 K；平仓后冷却 3 个交易日；现金不允许为负。
- 期末默认按最后收盘价估值，可选强平。
- 账户恒等：`净值 = 现金 + 持仓市值`，实测最大误差 6e-9，无负现金日。

## 4. 事件研究

- 信号日 T → T+1 开盘买入 → 持有 h 日收盘卖出，扣除双边成本与滑点。
- 同一股票两次信号间隔 < h 日时剔除后者，避免重叠样本。
- T+1 一字涨停无法买入的信号剔除。
- 输出：样本数、平均/中位收益、胜率、平均盈亏、超额（vs 等权基准）、t 值、MFE/MAE。
- 拆分维度：`ALL`、`MONTH:yyyy-MM`、`STATE:BULL/BEAR`、`BOARD:MAIN/GEM/STAR`、`SCORE:TOP20/BOT20`。

## 5. 数据表

`quant_data_snapshot`、`quant_backtest_task`、`quant_backtest_metric`、`quant_backtest_equity`、
`quant_backtest_trade`、`quant_event_study`、`quant_signal_daily`。
建表脚本 `src/main/resources/quant_schema.sql`，由 `QuantSchemaInitializer` 在应用就绪后幂等执行。

## 6. 性能实测（本机，2024-11-06 ~ 2026-11-13，4606 只 / 491 日 / 154.6 万行）

| 操作 | 耗时 |
| --- | --- |
| 面板首次加载 | 8.2 ~ 9.3 s |
| 单策略回测（含事件研究） | 0.4 ~ 1.7 s |
| metrics / equity / trades / events | 6 / 8 / 8 / 22 ms |
| 每日选股（5 策略，首次） | 2.8 ~ 3.5 s |
| 每日选股（缓存命中） | 0.02 s |

## 7. 已知边界

- 库中不含已退市股票，存在幸存者偏差；结果页需保留该提示。
- 无指数日线，基准为股票池等权指数；无股票名称/行业表，界面只展示代码与市场。
- 样本约 2 年（491 个交易日），可能不含完整牛熊周期，结论不宜外推。
- 回测只读 `stock_daily_all`，不修改任何行情表。
