# 数据查看可视化接口说明

基础路径：`http://localhost:8080`（代码里默认端口 8080）。
所有接口统一返回：

```json
{ "code": 0, "message": "ok", "data": { }, "timestamp": 1793536168539 }
```

- `code = 0` 成功；非 0 时 `message` 为原因，HTTP 状态码同步为 400 / 404 / 500。
- 空值字段不输出（`spring.jackson.default-property-inclusion=non_null`）。
- 日期格式统一 `yyyy-MM-dd`。
- 可视化看板：启动后直接访问 `http://localhost:8080/`（`static/index.html`，ECharts 需联网加载 CDN）。

## 一、股票维度 `/api/stocks`

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/api/stocks` | 股票列表（分页 + 筛选 + 排序） | `keyword` `market` `exchange` `board` `industry` `status` `listedOnly` `ipoFrom` `ipoTo` `sort` `order` `page` `size` |
| GET | `/api/stocks/suggest` | 代码/名称联想 | `keyword`（必填）`limit` |
| GET | `/api/stocks/filters` | 板块、行业、交易所下拉项 | - |
| GET | `/api/stocks/{code}` | 基础信息 | - |
| GET | `/api/stocks/{code}/quote` | 最新行情快照 | - |
| GET | `/api/stocks/{code}/daily` | 日线明细分页（最新在前） | `from` `to` `adjustFlag` `page` `size` |
| GET | `/api/stocks/{code}/kline` | K 线 + 技术指标 | `period=day/week/month` `adjustFlag` `from` `to` `limit` `withIndicators` |
| GET | `/api/stocks/{code}/stats` | 区间统计 | `from` `to` `adjustFlag` |
| GET | `/api/stocks/{code}/data-range` | 数据覆盖范围 | - |

### 1. 列表

```
GET /api/stocks?keyword=600&board=主板&listedOnly=true&sort=ipoDate&order=desc&page=1&size=20
```

`sort` 白名单：`code` `name` `ipoDate` `market` `industry` `updatedAt`。

### 2. K 线（核心接口）

```
GET /api/stocks/600215/kline?period=day&limit=250&withIndicators=true
```

返回结构（`bars` 按时间升序，可直接给 ECharts candlestick / 轻量图表库）：

```json
{
  "code": "600215", "name": "派斯林", "period": "day", "adjustFlag": 2,
  "startDate": "2025-11-03", "endDate": "2026-10-30", "count": 241,
  "bars": [{
    "tradeDate": "2026-10-30",
    "open": 5.70, "high": 6.37, "low": 5.65, "close": 6.37, "preclose": 5.79,
    "volume": 43181779, "amount": 270423117.99,
    "pctChg": 10.0173, "chgAmt": 0.58, "turnover": 9.4514, "amplitude": 12.4352,
    "isSt": 0, "tradeStatus": 1,
    "ma5": 5.981, "ma10": 5.902, "ma20": 5.874, "ma60": 6.102,
    "dif": 0.018, "dea": 0.001, "macd": 0.0339,
    "rsi6": 62.35, "rsi12": 55.11, "rsi24": 51.02,
    "bollUpper": 6.421, "bollMid": 5.874, "bollLower": 5.327
  }]
}
```

- `period=week/month` 由日线在服务端聚合：开=首日开、收=末日收、高低=区间极值、量额换手累加、涨跌幅按区间前收盘重算。
- 指标：MA5/10/20/60、MACD(12,26,9，柱=2×(DIF-DEA))、RSI(6/12/24，Wilder 平滑)、BOLL(20,2σ)；样本不足时字段缺省。
- `withIndicators=false` 可关闭指标以减小响应体。

### 3. 区间统计

```
GET /api/stocks/600215/stats?from=2026-01-01&to=2026-10-30
```

返回：`returnPct` 区间涨跌幅、`highestClose/lowestClose`（含日期）、`maxPctChg/minPctChg`（含日期）、`totalAmount/avgAmount/avgTurnover`、`annualizedVolatility`（日涨跌幅标准差 × √252）。

## 二、市场维度 `/api/market`

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/api/market/overview` | 市场概览 | `date`（缺省=最新交易日） |
| GET | `/api/market/calendar` | 最近交易日列表 | `limit`（默认 30） |
| GET | `/api/market/ranking` | 榜单 | `type` `date` `limit` `board` `includeSt` |
| GET | `/api/market/distribution` | 涨跌幅分布 | `date` |
| GET | `/api/market/sectors` | 板块/行业表现 | `dimension=board/industry` `date` `limit` |
| GET | `/api/market/breadth` | 市场宽度时序 | `from` `to` `days`（默认 60） |

`ranking.type`：`gainers` 涨幅榜、`losers` 跌幅榜、`amount` 成交额、`volume` 成交量、`turnover` 换手率、`amplitude` 振幅、`pe` 市盈率、`pb` 市净率。

概览返回：

```json
{
  "tradeDate": "2026-10-30",
  "totalStocks": 218, "upCount": 62, "downCount": 147, "flatCount": 9,
  "limitUpCount": 6, "limitDownCount": 1, "suspendedCount": 0, "stCount": 16,
  "upRatio": 28.44, "totalVolume": 8508537482,
  "totalAmount": 81201901443.35, "avgPctChg": -1.2959, "avgTurnover": 2.1911
}
```

> 涨停/跌停按 `|pct_chg| >= 9.8` 近似，不同板块（创业板/科创板 20%、北交所 30%）涨跌幅限制不同，仅作情绪参考。

## 三、量化评分 `/api/rankings`

评分数据来自 `stock_score_daily`，由 Python 评分任务按交易日生成。默认使用前复权 `adjustFlag=2`。

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/api/rankings/scores` | 多因子综合评分排行 | `date` `adjustFlag` `keyword` `minScore` `market` `industry` `page` `size` |
| GET | `/api/rankings/scores/dates` | 可用评分日期 | `adjustFlag` |
| POST | `/api/rankings/scores/rebuild` | 使用最新已入库日线实时重算评分 | `date` `adjustFlag` |

示例：

```
GET /api/rankings/scores?date=2026-10-30&adjustFlag=2&minScore=60&page=1&size=50
```

返回字段包含 `totalScore`、`valueScore`、`momentumScore`、`trendScore`、`riskScore`、`liquidityScore`，以及收盘价、21/63/126/252 日收益率、60 日波动率、252 日最大回撤和估值字段。

## 四、短期持有预测 `/api/short-term`

短期策略以最新日线为基准，输出 5、10、15、20 个交易日四个预测区间；20 个交易日作为最长约一个月的持有期。模型使用历史相似形态的未来收益分布，返回预估收益率、达到 +15% 的经验概率和置信度，不代表确定收益。

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/api/short-term/forecasts` | 短期预测排行 | `date` `adjustFlag` `keyword` `minProbability` `page` `size` |
| GET | `/api/short-term/forecasts/dates` | 可用预测日期 | `adjustFlag` |
| POST | `/api/short-term/forecasts/rebuild` | 使用最新日线重算预测 | `date` `adjustFlag` |

示例：

```
POST /api/short-term/forecasts/rebuild?adjustFlag=2
GET /api/short-term/forecasts?adjustFlag=2&minProbability=0.10&page=1&size=50
```

响应中的 `expected5/10/15/20` 是对应交易日的预估收益率，`probability5/10/15/20` 是历史相似样本达到至少 15% 的比例，`confidence20` 是相似样本数量和相似度共同形成的置信度。

### 按次保存的逐日预测

页面“短期持有预测”使用下面这组新接口。每次生成都会在 `stock_forecast_run` 创建批次，
在 `stock_forecast_prediction` 中为每只股票保存未来第 1～20 个交易日的预测，不会覆盖旧批次。
新交易日行情入库后调用评估接口，会把实际日收益、实际累计收益和预测误差写回原预测记录。

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/api/short-term/history/runs` | 查询历史预测批次 | `adjustFlag` `limit` |
| GET | `/api/short-term/history/source-dates` | 查询可作为预测日的真实行情日期 | `adjustFlag` `limit` |
| POST | `/api/short-term/history/runs/rebuild` | 新建一批 1～20 日预测 | 查询参数 `date` `adjustFlag` |
| GET | `/api/short-term/history/runs/{runId}/summary` | 某批次股票综合排行 | `keyword` `minProbability` `sortBy` `sortDirection` `page` `size` |
| GET | `/api/short-term/history/runs/{runId}/stocks/{code}` | 某只股票逐日明细 | `runId` `code` |
| POST | `/api/short-term/history/runs/{runId}/evaluate` | 回填已发生的实际行情 | `runId` |

预测批次相关表在 `src/main/resources/score_schema.sql` 中，首次部署时请执行其中新增的
`stock_forecast_run` 和 `stock_forecast_prediction` 建表语句。逐日预测采用距离加权的历史相似
形态模型：匹配 5/10/20 日收益、20 日均线趋势和成交额状态，计算未来 20 个交易日的逐日收益路径；
排行综合分为累计预估收益 55%、达到 +15% 的历史概率 35%、置信度 10%。这些是统计指标，不构成收益保证。

`sortBy` 支持 `score20`、`predictedCum5`、`predictedCum10`、`predictedCum15`、`predictedCum20`；
`sortDirection` 支持 `asc` 和 `desc`，默认降序。

## 五、页面触发的数据抓取 `/api/data-crawler`

“数据抓取”页面用于单独触发 Java 版 BaoStock 增量采集任务，不需要启动 Python 进程。
后端流程与 Python `data/main.py` 对齐：查询最近可用股票列表，按代码前缀筛选，按每只股票和复权口径
读取数据库最后交易日，只请求缺失的日线区间，并批量 upsert 到 `stock_info` 和分表
`stock_daily_00`、`stock_daily_30`、`stock_daily_60`。统一视图 `stock_daily_all` 只合并这三张新分表，
现有评分、行情和预测接口会自动读取对应分表；单只股票详情则按代码前缀直接查询对应物理表。
任务在后台执行，页面通过任务接口轮询进度；同一时间只允许一个抓取任务运行。

| 方法 | 路径 | 说明 | 主要参数 |
| --- | --- | --- | --- |
| POST | `/api/data-crawler/tasks` | 启动抓取任务 | `limit` `daysBack` `fqType` `storeRawPrices` `codePrefixes` `requestDelayMillis` `refreshStockList` |
| GET | `/api/data-crawler/tasks/{id}` | 查询任务进度 | - |
| GET | `/api/data-crawler/tasks` | 查询最近任务 | `limit` |

示例：

```json
POST /api/data-crawler/tasks
{
  "limit": 0,
  "daysBack": 548,
  "fqType": 1,
  "storeRawPrices": true,
  "codePrefixes": "00,30,60",
  "requestDelayMillis": 1500,
  "refreshStockList": false
}
```

- `fqType=1` 前复权、`2` 后复权、`0` 不复权；`storeRawPrices=true` 时会额外保存不复权数据。
- `limit=0` 表示处理全部符合前缀的股票；首次抓取最多回看 3650 个自然日。
- `refreshStockList=false` 时优先复用 `stock_info` 中已有股票池；设为 `true` 才强制重新请求 BaoStock 股票列表。
- 每次任务启动会批量读取股票池的最早和最新交易日期：目标起始日之前缺数据时补历史前段，最新日期落后今天时补最新后段，两端都覆盖时直接跳过。
- `daysBack=200`、`400` 分别表示最近 200、400 个自然日，BaoStock 返回其中实际存在的交易日。
- `crawl_log` 用于保存任务批次、成功/失败数量、开始/结束时间和错误备注，建表语句已加入 `score_schema.sql`。
- 新增抓取数据按代码前缀分表；当前只支持 00、30、60，分别写入 `stock_daily_00`、`stock_daily_30`、`stock_daily_60`，不再回退旧表。

## 六、必须知道的库表特性（影响统计口径）

实测数据里，**同一 `(code, trade_date)` 存在多条记录**，区别只在 `adjustflag`（2=前复权、3=不复权），说明实际表的主键/唯一键包含了 `adjustflag`，与建表语句里的 `PRIMARY KEY (code, trade_date)` 不一致。

代码里已做两件事：

1. **单只股票查询**：未显式传 `adjustFlag` 时，自动取该股票记录最多的口径（`StockDailyRepository#preferredAdjustFlag`，通常是 2 前复权），避免 K 线出现重复日期。
2. **市场级统计**：成交量、成交额、涨跌幅等与复权无关，先按 `code` 去重（`MAX()`）再聚合，避免家数、成交额翻倍；榜单类需要价格字段，用相关子查询只取当天 `MIN(adjustflag)` 的那一行，保证口径统一。

建议顺手加索引（不强制，但数据量上来后差别很大）：

```sql
ALTER TABLE stock_daily_00 ADD INDEX idx_daily_date (`trade_date`);
ALTER TABLE stock_daily_00 ADD INDEX idx_daily_code_date_flag (`code`, `trade_date`, `adjustflag`);
ALTER TABLE stock_daily_30 ADD INDEX idx_daily_date (`trade_date`);
ALTER TABLE stock_daily_30 ADD INDEX idx_daily_code_date_flag (`code`, `trade_date`, `adjustflag`);
ALTER TABLE stock_daily_60 ADD INDEX idx_daily_date (`trade_date`);
ALTER TABLE stock_daily_60 ADD INDEX idx_daily_code_date_flag (`code`, `trade_date`, `adjustflag`);
ALTER TABLE stock_info  ADD INDEX idx_info_board (`board`);
ALTER TABLE stock_info  ADD INDEX idx_info_industry (`industry`);
```

## 七、前端接入要点

- 涨红跌绿（A 股习惯），页面里 `--up: #d8392b`、`--down: #17924e`。
- K 线 `[open, close, low, high]` 直接对应 ECharts candlestick 的顺序；成交量按 `close >= open` 染色。
- 分页接口统一返回 `items/total/page/size/pages`，可直接对接分页组件。
- 跨域已在 `WebConfig` 放开 `/api/**`，前端独立部署时无需额外配置。
