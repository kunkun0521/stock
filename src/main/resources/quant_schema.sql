-- =====================================================================
-- 量化策略研究与回测平台（/api/quant）建表脚本
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，由 QuantSchemaInitializer 在启动后执行。
-- 只读 stock_daily_all（adjustflag=2），不改动任何行情表。
-- =====================================================================

-- 行情面板快照：记录每次加载的行数/区间/质检摘要，保证回测可追溯。
CREATE TABLE IF NOT EXISTS `quant_data_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `snapshot_key` VARCHAR(64) NOT NULL COMMENT '快照键：R行数-D最新交易日',
  `row_count` BIGINT NOT NULL DEFAULT 0,
  `stock_count` INT NOT NULL DEFAULT 0,
  `date_from` DATE DEFAULT NULL,
  `date_to` DATE DEFAULT NULL,
  `quality_json` TEXT,
  `load_cost_ms` BIGINT DEFAULT NULL,
  `loaded_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quant_snapshot_key` (`snapshot_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='量化平台行情面板快照';

-- 回测任务（同步承载事件研究：同一份信号只算一次，事件结果按 task_id 关联）
CREATE TABLE IF NOT EXISTS `quant_backtest_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `strategy_key` VARCHAR(64) NOT NULL,
  `strategy_version` VARCHAR(16) NOT NULL DEFAULT '1',
  `params_json` TEXT,
  `config_json` TEXT,
  `universe_json` TEXT,
  `start_date` DATE DEFAULT NULL,
  `end_date` DATE DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELED',
  `progress` INT NOT NULL DEFAULT 0,
  `stage` VARCHAR(64) DEFAULT NULL,
  `data_snapshot` VARCHAR(64) DEFAULT NULL,
  `error_msg` VARCHAR(1000) DEFAULT NULL,
  `summary_json` TEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `duration_ms` BIGINT DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_quant_task_strategy` (`strategy_key`, `created_at`),
  KEY `idx_quant_task_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='量化回测任务';

-- 绩效指标：ALL / IS / OOS 三份
CREATE TABLE IF NOT EXISTS `quant_backtest_metric` (
  `task_id` BIGINT NOT NULL,
  `split_type` VARCHAR(8) NOT NULL COMMENT 'ALL/IS/OOS',
  `metrics_json` TEXT,
  PRIMARY KEY (`task_id`, `split_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测绩效指标';

-- 每日净值
CREATE TABLE IF NOT EXISTS `quant_backtest_equity` (
  `task_id` BIGINT NOT NULL,
  `trade_date` DATE NOT NULL,
  `nav` DECIMAL(18,8) NOT NULL,
  `cash` DECIMAL(20,4) NOT NULL,
  `position_value` DECIMAL(20,4) NOT NULL,
  `benchmark_nav` DECIMAL(18,8) DEFAULT NULL,
  `drawdown` DECIMAL(12,6) DEFAULT NULL,
  `position_count` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`task_id`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测每日净值';

-- 交易明细
CREATE TABLE IF NOT EXISTS `quant_backtest_trade` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `seq` INT NOT NULL,
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `entry_date` DATE NOT NULL,
  `entry_price` DECIMAL(12,4) NOT NULL,
  `exit_date` DATE DEFAULT NULL,
  `exit_price` DECIMAL(12,4) DEFAULT NULL,
  `shares` DECIMAL(18,4) NOT NULL,
  `pnl` DECIMAL(20,4) DEFAULT NULL,
  `pnl_pct` DECIMAL(12,6) DEFAULT NULL,
  `hold_days` INT DEFAULT NULL,
  `exit_reason` VARCHAR(64) DEFAULT NULL,
  `entry_score` DECIMAL(10,4) DEFAULT NULL,
  `mae` DECIMAL(12,6) DEFAULT NULL,
  `mfe` DECIMAL(12,6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_quant_trade_task` (`task_id`, `entry_date`),
  KEY `idx_quant_trade_code` (`task_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测交易明细';

-- 事件研究汇总：horizon × 拆分维度
CREATE TABLE IF NOT EXISTS `quant_event_study` (
  `task_id` BIGINT NOT NULL,
  `horizon` INT NOT NULL,
  `split_key` VARCHAR(32) NOT NULL COMMENT 'ALL/MONTH:yyyy-MM/STATE:BULL/BOARD:MAIN/SCORE:TOP20',
  `cnt` INT NOT NULL DEFAULT 0,
  `mean_ret` DECIMAL(12,6) DEFAULT NULL,
  `median_ret` DECIMAL(12,6) DEFAULT NULL,
  `win_rate` DECIMAL(8,4) DEFAULT NULL,
  `avg_win` DECIMAL(12,6) DEFAULT NULL,
  `avg_loss` DECIMAL(12,6) DEFAULT NULL,
  `excess_mean` DECIMAL(12,6) DEFAULT NULL,
  `t_stat` DECIMAL(10,4) DEFAULT NULL,
  `mfe_mean` DECIMAL(12,6) DEFAULT NULL,
  `mae_mean` DECIMAL(12,6) DEFAULT NULL,
  PRIMARY KEY (`task_id`, `horizon`, `split_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件研究统计';

-- 每日选股信号（幂等覆盖）
CREATE TABLE IF NOT EXISTS `quant_signal_daily` (
  `scan_date` DATE NOT NULL,
  `strategy_key` VARCHAR(64) NOT NULL,
  `params_hash` VARCHAR(32) NOT NULL,
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `signal_type` VARCHAR(8) NOT NULL DEFAULT 'BUY',
  `score` DECIMAL(10,4) DEFAULT NULL,
  `detail_json` TEXT,
  `stop_price` DECIMAL(12,4) DEFAULT NULL,
  `close_price` DECIMAL(12,4) DEFAULT NULL,
  `ref_json` TEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`scan_date`, `strategy_key`, `params_hash`, `code`, `market`, `signal_type`),
  KEY `idx_quant_signal_date` (`scan_date`, `strategy_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日选股信号';
