基于以下表的数据，如何利用遗传算法做一个股票看涨看跌的功能，预测未来每只股票1天，3天，5天，10天，15天的，基于历史数据，通过不断的进化。淘汰，
并且把淘汰的股票记录下来。能够查看哪些历史预测正确，预测不准确的可视化看板，方便回顾。给我一个需求设计文档，采用什么模型，公式，可以新建表，但
给出表结构的字段描述，表干嘛的。其次，可以结合业内的专业的模型，以及以下表的历史数据，自行新增功能，功能需要描述清楚，怎么使用。给我一份需求文档，可以直接把文档给AI工具，
AI就能根据需求文档编码实现。
create table stock_daily
(
code        varchar(10)                           not null comment '股票代码，不含交易所前缀',
market      char(2)                               not null comment '交易所：SH=上海，SZ=深圳',
trade_date  date                                  not null comment '交易日期',
open        decimal(12, 4)                        null comment '开盘价，元；复权口径见adjustflag',
close       decimal(12, 4)                        null comment '收盘价，元；复权口径见adjustflag',
high        decimal(12, 4)                        null comment '最高价，元；复权口径见adjustflag',
low         decimal(12, 4)                        null comment '最低价，元；复权口径见adjustflag',
preclose    decimal(12, 4)                        null comment '前收盘价，元；复权口径见adjustflag',
volume      decimal(20, 2)                        null comment '成交量，股',
amount      decimal(24, 2)                        null comment '成交额，元',
amplitude   decimal(10, 4)                        null comment '振幅百分比，(最高价-最低价)/前收盘价×100',
pct_chg     decimal(10, 4)                        null comment '涨跌幅百分比',
chg_amt     decimal(12, 4)                        null comment '涨跌额，元',
turnover    decimal(10, 4)                        null comment '换手率百分比',
adjustflag  tinyint     default 2                 not null comment '价格复权标记：1=后复权，2=前复权，3=不复权',
tradestatus tinyint                               null comment '交易状态：1=交易，0=停牌',
is_st       tinyint                               null comment '是否ST：1=是，0=否',
pe_ttm      decimal(20, 6)                        null comment '滚动市盈率（TTM）',
pb_mrq      decimal(20, 6)                        null comment '市净率（MRQ）',
ps_ttm      decimal(20, 6)                        null comment '滚动市销率（TTM）',
pcf_ncf_ttm decimal(20, 6)                        null comment '滚动市现率（TTM，经营现金流口径）',
data_source varchar(32) default 'baostock'        not null comment '数据来源标识',
created_at  datetime    default CURRENT_TIMESTAMP not null comment '记录首次写入时间',
updated_at  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '记录最近更新时间',
primary key (code, market, trade_date, adjustflag)
);
create table stock_score_daily
(
code            varchar(10)                        not null comment '股票代码',
market          char(2)                            not null comment '市场代码(SH/SZ/BJ)',
trade_date      date                               not null comment '交易日期',
adjustflag      tinyint  default 2                 not null comment '复权标志(1前复权 2不复权 3后复权)',
total_score     decimal(10, 4)                     null comment '综合评分',
value_score     decimal(10, 4)                     null comment '价值因子评分',
momentum_score  decimal(10, 4)                     null comment '动量因子评分',
trend_score     decimal(10, 4)                     null comment '趋势因子评分',
risk_score      decimal(10, 4)                     null comment '风险因子评分',
liquidity_score decimal(10, 4)                     null comment '流动性因子评分',
close           decimal(12, 4)                     null comment '收盘价',
pe_ttm          decimal(20, 6)                     null comment '市盈率(TTM)',
pb_mrq          decimal(20, 6)                     null comment '市净率(MRQ)',
ps_ttm          decimal(20, 6)                     null comment '市销率(TTM)',
pcf_ncf_ttm     decimal(20, 6)                     null comment '市现率(经营现金流TTM)',
ret_21          decimal(12, 6)                     null comment '21日收益率',
ret_63          decimal(12, 6)                     null comment '63日收益率',
ret_126         decimal(12, 6)                     null comment '126日收益率',
ret_252         decimal(12, 6)                     null comment '252日收益率',
vol_60          decimal(12, 6)                     null comment '60日波动率',
drawdown_252    decimal(12, 6)                     null comment '252日最大回撤',
amount_ratio_20 decimal(12, 6)                     null comment '20日成交额变化比率',
turnover_20     decimal(12, 6)                     null comment '20日平均换手率',
created_at      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
updated_at      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
primary key (code, market, trade_date, adjustflag)
);
---

## 6. GA 遗传算法预测接口（`/api/ga`）

完整公式与使用说明见 **`docs/功能与公式说明.md` 第 9 章**；此处只列接口。

### 6.1 看板查询（只读）

| 方法 | 路径 | 主要参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/overview` | horizon, from, to, board, strict, includeReplay | 总览 KPI：命中率、样本数、置信度 |
| GET | `/overview/trend` | horizon, window, from, to | 逐日命中率趋势 |
| GET | `/overview/calendar` | horizon, month | 月度日历热力 |
| GET | `/predictions` | from, to, board, inPool, sort, page, size | 预测明细 + 摘要 |
| GET | `/predictions/export` | 同上 | CSV 导出（UTF-8 BOM） |
| GET | `/stock/{market}/{code}` | horizon, from, to | 个股预测历史 |
| GET | `/analysis/buckets` | horizon, dim(vol/conf/score), from, to | 分桶准确率 |
| GET | `/analysis/reliability` | horizon, from, to | 可靠性曲线 |
| GET | `/analysis/confusion` | horizon, from, to | 混淆矩阵 |
| GET | `/analysis/top-errors` | horizon, topN | 最大错误 Top |
| GET | `/analysis/quantile` | horizon, from, to | 分位数收益表 |
| GET | `/pool` | status, reason, page, size | 股票池列表 |
| GET | `/pool/log` | code, market, from, to | 淘汰/复活日志 |
| GET | `/evolution/runs` | horizon, page, size | 进化批次 |
| GET | `/evolution/runs/{id}/generations` | — | 逐代统计 |
| GET | `/individuals` | horizon | 个体列表（染色体 JSON） |
| GET | `/champions` | horizon | 冠军集版本与成员 |
| GET | `/factors/ic` | horizon, days | 因子 IC 体检 |
| GET | `/picks` | date, topN, direction(1/-1), minConsensus | 多周期共识选股 |
| GET | `/config` | scope | 配置项（不传或空串=全部作用域） |
| GET | `/jobs/status` | — | 任务状态与最近结果（含 running 排队标记） |
| GET | `/jobs/log` | limit(默认 30，最大 200) | 最近任务执行日志（含真实 startedAt/finishedAt） |
| GET | `/jobs/schedule` | — | 定时调度：cron、下次触发时间、配置总开关与运行时开关、服务器时间 |

### 6.2 写操作

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/config` | 热更新配置 `{scope,paramKey,paramValue}`；跨域直连需后端放行 PUT（已配置） |
| POST | `/jobs/run` | 触发任务 `{job}`；job 取 `FeatureJob/PredictJob/VerifyJob/MetricJob/PoolJob/EvolveJob/CalibrateJob/FactorIcJob/ReplayJob/DailyPipeline/WeeklyEvolve` |
| POST | `/jobs/schedule/toggle` | 切换定时触发运行时开关 `{enabled}`，只影响 @Scheduled，不影响手动运行 |
| POST | `/jobs/schedule/fire` | 按定时规则立即执行一次 `{kind:daily\|weekly}`，与 @Scheduled 同一提交路径 |
| POST | `/pool/override` | 手工淘汰/复活/白名单 `{code,market,action,remark}`（remark 必填） |
| POST | `/evolution/run?horizon=` | 手动触发进化（不传 horizon 跑全部周期） |
| POST | `/evolution/replay` | 历史回放 `{from,to,step,horizons}` |
| POST | `/champions/promote?runId=&horizon=` | 指定 run 晋升冠军集 |
| POST | `/champions/rollback` | 回滚 `{horizon,versionTag}` |

### 6.3 使用顺序（首次）

`FeatureJob`（自动回填全历史特征）→ `EvolveJob` → `PredictJob` → `PoolJob` → 到期后 `VerifyJob` → `MetricJob`。
复盘数据需跑 `/evolution/replay` 生成历史预测并回填。
