# 股票分析与交易平台

## 1. 概述
本项目是基于 Java Spring Boot 的股票数据采集、存储、分析、预测及交易模拟平台。
- **REST API**：提供数据爬取、市场信息、预测、评分与交易等接口。
- **架构层次**
  - **Domain**：值对象、实体、DTO，描述股票数据、预测结果与交易配置。
  - **Repository**：Spring Data JPA 访问层，映射数据库表。
  - **Service**：业务逻辑层，负责调度 Repository、计算预测与评分。
  - **Controller**：对外 REST 接口。
  - **Scheduler**：定时任务，周期性爬取 K‑线数据。
  - **TLab**：轻量级回测框架（StrategyEngine、ExecutionSimulator 等），可读取策略文件、模拟交易并输出绩效。

## 2. 核心包
| 包名 | 主要职责 |
|------|----------|
| com.li.stock.controller | 市场、预测、爬取、评分、交易等 REST 接口 |
| com.li.stock.service | 业务逻辑，调用 Repository、整合其它服务 |
| com.li.stock.repository | JPA 数据访问层 |
| com.li.stock.domain | 领域对象：实体、VO、查询、请求 |
| com.li.stock.mapper | MyBatis Mapper 接口与 XML 配置 |
| com.li.stock.integration | 外部接口（如 BaoStockClient） |
| com.li.stock.tlab | 回测引擎与工具 |
| com.li.stock.util | 工具类（如 IndicatorCalculator、KlineAggregator） |

## 3. 关键功能

1. **数据爬取**
   - CrawlTaskController + CrawlTaskService：管理爬取任务。
   - DailyKlineScheduledCrawler：定时从外部接口抓取 K‑线并写入数据库。

2. **市场信息**
   - MarketController：返回当前市场快照与缓存数据。
   - MarketService：聚合数据库数据并缓存。

3. **预测**
   - ForecastController：触发预测。
   - ForecastService：基于历史数据与指标模型计算预测。
   - 预测模型存储在 domain/model/ForecastPrediction.java，通过 ForecastRepository 读写。

4. **评分与排名**
   - ScoreCalculationService：计算股票评分。
   - ScoreRankingController：返回排序后的排名列表。

5. **短期预测**
   - ShortTermForecastController：实时提供短期预测。
   - ShortTermForecastService：使用近期数据生成日预测。

6. **交易实验室（TLab）**
   - TLabService：执行回测。
   - StrategyEngine：解析策略文件，调用 EntryScanner、ExecutionSimulator 并计算费用。
   - 结果通过 TTradingController 与 TController 对外展示。

## 4. 数据流（示例：预测请求）
`
Client ➜ POST /api/forecast
    |--- ForecastController (校验) ➔ ForecastService
          |--- 使用 ForecastRepository 获取历史数据
          |--- 调用 IndicatorService 计算技术指标
          |--- 生成 ForecastPrediction
          |--- 通过 ForecastRepository 存储结果
    |--- 返回 ForecastResultVO
`

## 5. 配置
- src/main/resources/application.yaml：应用配置。
- score_schema.sql、	_lab_schema.sql：数据库脚本。
- src/main/resources/mapper：MyBatis XML 映射文件。

## 6. 如何运行
1. **构建**：./mvnw clean package
2. **运行**：java -jar target/stock-0.0.1-SNAPSHOT.jar
3. **访问**：默认端口 8080，例如 GET http://localhost:8080/api/market

## 7. 如何扩展
- **新增预测**：实现新的 ForecastService 方法并通过 Controller 暴露。
- **接入新数据源**：编写 Integration 客户端并集成至爬取服务。
- **添加新策略**：将策略文件放入 TLab 目录，通过 TTradingController 触发模拟。

---

*本文件提供项目的整体视图，若需查看详细 API 接口与 VO 定义，请参考 controller 包及对应的 VO 类。*
