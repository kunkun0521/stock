-- 评分表由 Python 评分任务或部署脚本创建；Java 服务只读该表。
CREATE TABLE IF NOT EXISTS `stock_score_daily` (
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `trade_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `total_score` DECIMAL(10,4) DEFAULT NULL,
  `value_score` DECIMAL(10,4) DEFAULT NULL,
  `momentum_score` DECIMAL(10,4) DEFAULT NULL,
  `trend_score` DECIMAL(10,4) DEFAULT NULL,
  `risk_score` DECIMAL(10,4) DEFAULT NULL,
  `liquidity_score` DECIMAL(10,4) DEFAULT NULL,
  `close` DECIMAL(12,4) DEFAULT NULL,
  `pe_ttm` DECIMAL(20,6) DEFAULT NULL,
  `pb_mrq` DECIMAL(20,6) DEFAULT NULL,
  `ps_ttm` DECIMAL(20,6) DEFAULT NULL,
  `pcf_ncf_ttm` DECIMAL(20,6) DEFAULT NULL,
  `ret_21` DECIMAL(12,6) DEFAULT NULL,
  `ret_63` DECIMAL(12,6) DEFAULT NULL,
  `ret_126` DECIMAL(12,6) DEFAULT NULL,
  `ret_252` DECIMAL(12,6) DEFAULT NULL,
  `vol_60` DECIMAL(12,6) DEFAULT NULL,
  `drawdown_252` DECIMAL(12,6) DEFAULT NULL,
  `amount_ratio_20` DECIMAL(12,6) DEFAULT NULL,
  `turnover_20` DECIMAL(12,6) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`,`market`,`trade_date`,`adjustflag`),
  KEY `idx_score_date` (`trade_date`,`adjustflag`,`total_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stock_forecast_daily` (
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `trade_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `horizon_days` SMALLINT NOT NULL COMMENT '预测持有期，交易日数量',
  `expected_return` DECIMAL(12,6) DEFAULT NULL COMMENT '预估收益率，小数表示',
  `target15_probability` DECIMAL(12,6) DEFAULT NULL COMMENT '历史相似样本达到15%的概率',
  `confidence` DECIMAL(12,6) DEFAULT NULL COMMENT '预测置信度，0到1',
  `forecast_score` DECIMAL(10,4) DEFAULT NULL COMMENT '短期预测综合分，0到100',
  `analog_count` INT NOT NULL DEFAULT 0 COMMENT '历史相似样本数量',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`,`market`,`trade_date`,`adjustflag`,`horizon_days`),
  KEY `idx_forecast_date` (`trade_date`,`adjustflag`,`horizon_days`,`forecast_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stock_forecast_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `forecast_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `source_latest_date` DATE NOT NULL,
  `model_version` VARCHAR(32) NOT NULL DEFAULT 'analog-v2',
  `stock_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_forecast_run_date` (`forecast_date`,`adjustflag`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stock_forecast_prediction` (
  `run_id` BIGINT NOT NULL,
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `forecast_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `horizon_days` SMALLINT NOT NULL COMMENT '从预测日开始的第几个交易日，1到20',
  `predicted_daily_return` DECIMAL(12,6) DEFAULT NULL COMMENT '该目标日预估日收益率',
  `predicted_cumulative_return` DECIMAL(12,6) DEFAULT NULL COMMENT '截至目标日累计预估收益率',
  `target15_probability` DECIMAL(12,6) DEFAULT NULL COMMENT '历史相似样本截至目标日达到15%的概率',
  `confidence` DECIMAL(12,6) DEFAULT NULL COMMENT '预测置信度，0到1',
  `forecast_score` DECIMAL(10,4) DEFAULT NULL COMMENT '预测综合分，0到100',
  `analog_count` INT NOT NULL DEFAULT 0,
  `actual_target_date` DATE DEFAULT NULL,
  `actual_daily_return` DECIMAL(12,6) DEFAULT NULL,
  `actual_cumulative_return` DECIMAL(12,6) DEFAULT NULL,
  `prediction_error` DECIMAL(12,6) DEFAULT NULL,
  `evaluation_status` VARCHAR(16) NOT NULL DEFAULT 'pending',
  `evaluated_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`run_id`,`code`,`market`,`horizon_days`),
  KEY `idx_prediction_stock` (`code`,`market`,`forecast_date`,`horizon_days`),
  KEY `idx_prediction_run_score` (`run_id`,`horizon_days`,`forecast_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 日线数据只使用按股票代码前缀拆分的三张物理表。
-- 不再依赖旧 stock_daily，避免应用启动时因旧表不存在而初始化失败。
CREATE TABLE IF NOT EXISTS `stock_daily_00` (
  `code` VARCHAR(10) NOT NULL COMMENT '股票代码，不含交易所前缀',
  `market` CHAR(2) NOT NULL COMMENT '交易所：SH=上海，SZ=深圳',
  `trade_date` DATE NOT NULL COMMENT '交易日期',
  `open` DECIMAL(12,4) DEFAULT NULL COMMENT '开盘价，元',
  `close` DECIMAL(12,4) DEFAULT NULL COMMENT '收盘价，元',
  `high` DECIMAL(12,4) DEFAULT NULL COMMENT '最高价，元',
  `low` DECIMAL(12,4) DEFAULT NULL COMMENT '最低价，元',
  `preclose` DECIMAL(12,4) DEFAULT NULL COMMENT '前收盘价，元',
  `volume` DECIMAL(20,2) DEFAULT NULL COMMENT '成交量，股',
  `amount` DECIMAL(24,2) DEFAULT NULL COMMENT '成交额，元',
  `amplitude` DECIMAL(10,4) DEFAULT NULL COMMENT '振幅百分比',
  `pct_chg` DECIMAL(10,4) DEFAULT NULL COMMENT '涨跌幅百分比',
  `chg_amt` DECIMAL(12,4) DEFAULT NULL COMMENT '涨跌额，元',
  `turnover` DECIMAL(10,4) DEFAULT NULL COMMENT '换手率百分比',
  `adjustflag` TINYINT NOT NULL DEFAULT 2 COMMENT '1=后复权，2=前复权，3=不复权',
  `tradestatus` TINYINT DEFAULT NULL COMMENT '1=交易，0=停牌',
  `is_st` TINYINT DEFAULT NULL COMMENT '1=ST，0=非ST',
  `pe_ttm` DECIMAL(20,6) DEFAULT NULL,
  `pb_mrq` DECIMAL(20,6) DEFAULT NULL,
  `ps_ttm` DECIMAL(20,6) DEFAULT NULL,
  `pcf_ncf_ttm` DECIMAL(20,6) DEFAULT NULL,
  `data_source` VARCHAR(32) NOT NULL DEFAULT 'baostock',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`,`market`,`trade_date`,`adjustflag`),
  KEY `idx_stock_daily_00_trade_date` (`trade_date`),
  KEY `idx_stock_daily_00_code_date_flag` (`code`,`trade_date`,`adjustflag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stock_daily_30` LIKE `stock_daily_00`;
CREATE TABLE IF NOT EXISTS `stock_daily_60` LIKE `stock_daily_00`;

-- 统一查询入口只合并三张新分表，评分、行情、预测等跨股票功能均从这里读取。
CREATE OR REPLACE VIEW `stock_daily_all` AS
SELECT `code`,`market`,`trade_date`,`open`,`close`,`high`,`low`,`preclose`,`volume`,`amount`,
       `amplitude`,`pct_chg`,`chg_amt`,`turnover`,`adjustflag`,`tradestatus`,`is_st`,
       `pe_ttm`,`pb_mrq`,`ps_ttm`,`pcf_ncf_ttm`,`data_source`
FROM `stock_daily_00`
UNION ALL
SELECT `code`,`market`,`trade_date`,`open`,`close`,`high`,`low`,`preclose`,`volume`,`amount`,
       `amplitude`,`pct_chg`,`chg_amt`,`turnover`,`adjustflag`,`tradestatus`,`is_st`,
       `pe_ttm`,`pb_mrq`,`ps_ttm`,`pcf_ncf_ttm`,`data_source`
FROM `stock_daily_30`
UNION ALL
SELECT `code`,`market`,`trade_date`,`open`,`close`,`high`,`low`,`preclose`,`volume`,`amount`,
       `amplitude`,`pct_chg`,`chg_amt`,`turnover`,`adjustflag`,`tradestatus`,`is_st`,
       `pe_ttm`,`pb_mrq`,`ps_ttm`,`pcf_ncf_ttm`,`data_source`
FROM `stock_daily_60`;

-- Java 页面触发的数据抓取任务日志，与 Python data/main.py 的 crawl_log 对齐。
CREATE TABLE IF NOT EXISTS `crawl_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_name` VARCHAR(100) NOT NULL,
  `total_count` INT NOT NULL DEFAULT 0,
  `success_count` INT NOT NULL DEFAULT 0,
  `fail_count` INT NOT NULL DEFAULT 0,
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'running',
  `remark` TEXT,
  PRIMARY KEY (`id`),
  KEY `idx_crawl_log_start_time` (`start_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
