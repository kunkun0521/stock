-- =============================================================================
-- 股票遗传算法涨跌预测系统 v1.0 —— 表结构
--
-- 纯新增：不修改任何现有表；启动时由 GaSchemaInitializer 幂等执行（CREATE TABLE IF NOT EXISTS）。
-- 命名约定：新表统一 ga_ 前缀，特征缓存表按规格叫 stock_feature_daily。
-- 价格口径：两表一律用同一个 adjustflag 数值（默认 2）关联，不做数值转换。
--   stock_daily_* 的注释是 1=后复权 2=前复权 3=不复权；
--   stock_score_daily 的注释是 1=前复权 2=不复权 3=后复权（相反）。
--   启动时抽样 200 行比对 close，偏差超 0.01% 写告警日志并在看板顶部提示。
-- market 一律用日线表的 CHAR(2)（SH / SZ），与 stock_info.exchange 关联。
-- 仅用于研究与复盘，输出不构成投资建议。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ga_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
  `param_key` VARCHAR(64) NOT NULL,
  `param_value` VARCHAR(512) DEFAULT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_config` (`scope`, `param_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GA 参数配置，周期级覆盖全局';

CREATE TABLE IF NOT EXISTS `ga_market_daily` (
  `trade_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `stock_cnt` INT NOT NULL DEFAULT 0,
  `mkt_ret_1` DECIMAL(10,6) DEFAULT NULL,
  `mkt_ret_5` DECIMAL(10,6) DEFAULT NULL,
  `mkt_ret_20` DECIMAL(10,6) DEFAULT NULL,
  `mkt_vol_20` DECIMAL(10,6) DEFAULT NULL,
  `breadth_up` DECIMAL(8,6) DEFAULT NULL,
  `avg_turnover` DECIMAL(10,4) DEFAULT NULL,
  `mkt_index` DECIMAL(16,6) DEFAULT NULL,
  `regime` TINYINT DEFAULT NULL COMMENT '1 上升 2 震荡 3 下跌',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`trade_date`, `adjustflag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='池内等权市场状态';

CREATE TABLE IF NOT EXISTS `stock_feature_daily` (
  `code` VARCHAR(10) NOT NULL,
  `market` CHAR(2) NOT NULL,
  `trade_date` DATE NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `feature_set_ver` VARCHAR(16) NOT NULL DEFAULT 'v1',
  -- v1 原始（未标准化）特征，列名即 FeatureKeys.ALL 的顺序
  `ret_1` DECIMAL(14,6) DEFAULT NULL, `ret_3` DECIMAL(14,6) DEFAULT NULL,
  `ret_5` DECIMAL(14,6) DEFAULT NULL, `ret_10` DECIMAL(14,6) DEFAULT NULL,
  `ret_21` DECIMAL(14,6) DEFAULT NULL, `ret_63` DECIMAL(14,6) DEFAULT NULL,
  `ret_126` DECIMAL(14,6) DEFAULT NULL, `ret_252` DECIMAL(14,6) DEFAULT NULL,
  `rel_ret_5` DECIMAL(14,6) DEFAULT NULL,
  `ma_gap_5` DECIMAL(14,6) DEFAULT NULL, `ma_gap_10` DECIMAL(14,6) DEFAULT NULL,
  `ma_gap_20` DECIMAL(14,6) DEFAULT NULL, `ma_gap_60` DECIMAL(14,6) DEFAULT NULL,
  `ma_slope_20` DECIMAL(14,6) DEFAULT NULL, `macd_hist` DECIMAL(14,6) DEFAULT NULL,
  `boll_pct_20` DECIMAL(14,6) DEFAULT NULL, `adx_14` DECIMAL(14,6) DEFAULT NULL,
  `rsi_6` DECIMAL(14,6) DEFAULT NULL, `rsi_14` DECIMAL(14,6) DEFAULT NULL,
  `kdj_j` DECIMAL(14,6) DEFAULT NULL, `cci_14` DECIMAL(14,6) DEFAULT NULL,
  `vol_20` DECIMAL(14,6) DEFAULT NULL, `vol_60` DECIMAL(14,6) DEFAULT NULL,
  `atr_14_pct` DECIMAL(14,6) DEFAULT NULL, `amp_5` DECIMAL(14,6) DEFAULT NULL,
  `dd_20` DECIMAL(14,6) DEFAULT NULL, `drawdown_252` DECIMAL(14,6) DEFAULT NULL,
  `downvol_20` DECIMAL(14,6) DEFAULT NULL, `beta_60` DECIMAL(14,6) DEFAULT NULL,
  `vr_5_20` DECIMAL(14,6) DEFAULT NULL, `amount_ratio_20` DECIMAL(14,6) DEFAULT NULL,
  `turnover_20` DECIMAL(14,6) DEFAULT NULL, `turn_5` DECIMAL(14,6) DEFAULT NULL,
  `turn_z_20` DECIMAL(14,6) DEFAULT NULL, `obv_slope_10` DECIMAL(14,6) DEFAULT NULL,
  `amihud_20` DECIMAL(14,6) DEFAULT NULL, `gap_pct` DECIMAL(14,6) DEFAULT NULL,
  `body_pct` DECIMAL(14,6) DEFAULT NULL, `upper_shadow` DECIMAL(14,6) DEFAULT NULL,
  `lower_shadow` DECIMAL(14,6) DEFAULT NULL, `close_pos` DECIMAL(14,6) DEFAULT NULL,
  `up_streak` DECIMAL(14,6) DEFAULT NULL, `limit_cnt_10` DECIMAL(14,6) DEFAULT NULL,
  `ep` DECIMAL(14,6) DEFAULT NULL, `bp` DECIMAL(14,6) DEFAULT NULL,
  `sp` DECIMAL(14,6) DEFAULT NULL, `cfp` DECIMAL(14,6) DEFAULT NULL,
  `loss_flag` DECIMAL(14,6) DEFAULT NULL,
  `total_score` DECIMAL(14,6) DEFAULT NULL, `value_score` DECIMAL(14,6) DEFAULT NULL,
  `momentum_score` DECIMAL(14,6) DEFAULT NULL, `trend_score` DECIMAL(14,6) DEFAULT NULL,
  `risk_score` DECIMAL(14,6) DEFAULT NULL, `liquidity_score` DECIMAL(14,6) DEFAULT NULL,
  `mkt_ret_5` DECIMAL(14,6) DEFAULT NULL, `mkt_vol_20` DECIMAL(14,6) DEFAULT NULL,
  `breadth_up` DECIMAL(14,6) DEFAULT NULL, `regime_code` DECIMAL(14,6) DEFAULT NULL,
  `valid_flag` TINYINT NOT NULL DEFAULT 1 COMMENT '1 可训练/预测；0 停牌或缺失过多',
  `limit_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '1 涨停 -1 跌停 0 其它',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`, `market`, `trade_date`, `adjustflag`),
  KEY `idx_sfd_date` (`trade_date`, `adjustflag`),
  KEY `idx_sfd_code_date` (`code`, `market`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GA v1 原始特征缓存，标准化在内存按日截面完成';

CREATE TABLE IF NOT EXISTS `ga_run` (
  `run_id` BIGINT NOT NULL AUTO_INCREMENT,
  `horizon` SMALLINT NOT NULL,
  `run_type` VARCHAR(16) NOT NULL DEFAULT 'EVOLVE',
  `as_of_date` DATE NOT NULL,
  `train_start` DATE DEFAULT NULL, `train_end` DATE DEFAULT NULL,
  `valid_start` DATE DEFAULT NULL, `valid_end` DATE DEFAULT NULL,
  `holdout_start` DATE DEFAULT NULL, `holdout_end` DATE DEFAULT NULL,
  `feature_set_json` JSON DEFAULT NULL,
  `params_json` JSON DEFAULT NULL,
  `random_seed` BIGINT NOT NULL DEFAULT 0,
  `pop_size` INT NOT NULL DEFAULT 0,
  `generations_done` INT NOT NULL DEFAULT 0,
  `best_fitness` DECIMAL(10,6) DEFAULT NULL,
  `best_individual_id` BIGINT DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  `warn_msg` VARCHAR(512) DEFAULT NULL,
  `error_msg` VARCHAR(1024) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL, `finished_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`run_id`),
  KEY `idx_ga_run_h` (`horizon`, `as_of_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='进化运行元信息，保证可复现';

CREATE TABLE IF NOT EXISTS `ga_generation_stat` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `generation` INT NOT NULL,
  `best_fitness` DECIMAL(10,6) DEFAULT NULL, `avg_fitness` DECIMAL(10,6) DEFAULT NULL,
  `median_fitness` DECIMAL(10,6) DEFAULT NULL, `worst_fitness` DECIMAL(10,6) DEFAULT NULL,
  `diversity` DECIMAL(10,6) DEFAULT NULL,
  `culled_count` INT NOT NULL DEFAULT 0,
  `immigrants_count` INT NOT NULL DEFAULT 0,
  `mutation_rate_w` DECIMAL(8,6) DEFAULT NULL,
  `best_balacc_valid` DECIMAL(10,6) DEFAULT NULL,
  `best_ic_valid` DECIMAL(10,6) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_gen` (`run_id`, `generation`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每代适应度分布与淘汰数量';

CREATE TABLE IF NOT EXISTS `ga_individual` (
  `individual_id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `horizon` SMALLINT NOT NULL,
  `generation` INT NOT NULL DEFAULT 0,
  `parent_ids` VARCHAR(64) DEFAULT NULL,
  `chromosome_json` JSON NOT NULL,
  `n_active_features` INT NOT NULL DEFAULT 0,
  `fitness` DECIMAL(10,6) DEFAULT NULL,
  `fit_train` DECIMAL(10,6) DEFAULT NULL, `fit_valid` DECIMAL(10,6) DEFAULT NULL,
  `balacc_valid` DECIMAL(10,6) DEFAULT NULL, `ic_valid` DECIMAL(10,6) DEFAULT NULL,
  `coverage_valid` DECIMAL(10,6) DEFAULT NULL, `brier_valid` DECIMAL(10,6) DEFAULT NULL,
  `balacc_holdout` DECIMAL(10,6) DEFAULT NULL, `ic_holdout` DECIMAL(10,6) DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'HOF',
  `retire_reason` VARCHAR(32) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `promoted_at` DATETIME DEFAULT NULL, `retired_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`individual_id`),
  KEY `idx_ga_ind_h_status` (`horizon`, `status`),
  KEY `idx_ga_ind_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='名人堂/候选/冠军/退役个体';

CREATE TABLE IF NOT EXISTS `ga_champion_set` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `horizon` SMALLINT NOT NULL,
  `version_tag` VARCHAR(32) NOT NULL,
  `individual_id` BIGINT NOT NULL,
  `rank_no` TINYINT NOT NULL DEFAULT 1,
  `weight` DECIMAL(8,6) NOT NULL DEFAULT 1,
  `is_current` TINYINT NOT NULL DEFAULT 1,
  `effective_from` DATE DEFAULT NULL, `effective_to` DATE DEFAULT NULL,
  `source_run_id` BIGINT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ga_cs` (`horizon`, `is_current`, `version_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冠军集版本，支持对比与回滚';

CREATE TABLE IF NOT EXISTS `ga_individual_perf` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `individual_id` BIGINT NOT NULL,
  `horizon` SMALLINT NOT NULL,
  `as_of_date` DATE NOT NULL,
  `window_days` INT NOT NULL,
  `n_samples` INT NOT NULL DEFAULT 0,
  `balacc` DECIMAL(10,6) DEFAULT NULL, `ic` DECIMAL(10,6) DEFAULT NULL,
  `brier` DECIMAL(10,6) DEFAULT NULL, `coverage` DECIMAL(10,6) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_perf` (`individual_id`, `as_of_date`, `window_days`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冠军/影子个体滚动实盘表现';

CREATE TABLE IF NOT EXISTS `ga_prediction` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `trade_date` DATE NOT NULL,
  `code` VARCHAR(10) NOT NULL, `market` CHAR(2) NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `horizon` SMALLINT NOT NULL,
  `model_role` VARCHAR(8) NOT NULL DEFAULT 'CHAMPION',
  `is_replay` TINYINT NOT NULL DEFAULT 0,
  `in_pool` TINYINT NOT NULL DEFAULT 1,
  `champion_version` VARCHAR(32) DEFAULT NULL,
  `pred_direction` TINYINT NOT NULL DEFAULT 0,
  `prob_up` DECIMAL(8,6) DEFAULT NULL,
  `confidence` DECIMAL(8,6) DEFAULT NULL,
  `score_raw` DECIMAL(10,6) DEFAULT NULL,
  `base_close` DECIMAL(12,4) DEFAULT NULL,
  `limit_flag` TINYINT NOT NULL DEFAULT 0, `is_st` TINYINT NOT NULL DEFAULT 0,
  `vol_20` DECIMAL(14,6) DEFAULT NULL, `amount_ma_20` DECIMAL(20,4) DEFAULT NULL,
  `pe_ttm` DECIMAL(20,6) DEFAULT NULL,
  `regime` TINYINT DEFAULT NULL,
  `explain_json` JSON DEFAULT NULL,
  `status` VARCHAR(8) NOT NULL DEFAULT 'PENDING',
  `target_date` DATE DEFAULT NULL,
  `actual_close` DECIMAL(12,4) DEFAULT NULL,
  `actual_return` DECIMAL(10,6) DEFAULT NULL,
  `actual_direction` TINYINT DEFAULT NULL,
  `is_correct` TINYINT DEFAULT NULL,
  `is_effective` TINYINT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `verified_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_pred` (`trade_date`, `code`, `market`, `adjustflag`, `horizon`, `model_role`, `is_replay`),
  KEY `idx_ga_pred_status` (`horizon`, `status`, `trade_date`),
  KEY `idx_ga_pred_code` (`code`, `market`, `trade_date`),
  KEY `idx_ga_pred_target` (`target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测与到期回填，复盘主数据源';

CREATE TABLE IF NOT EXISTS `ga_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `as_of_date` DATE NOT NULL,
  `horizon` SMALLINT NOT NULL,
  `scope_type` VARCHAR(16) NOT NULL DEFAULT 'ALL',
  `scope_value` VARCHAR(16) NOT NULL DEFAULT '',
  `window_days` INT NOT NULL DEFAULT 20,
  `is_replay` TINYINT NOT NULL DEFAULT 0,
  `n_pred` INT NOT NULL DEFAULT 0, `n_verified` INT NOT NULL DEFAULT 0,
  `n_correct` INT NOT NULL DEFAULT 0,
  `hit_rate` DECIMAL(8,6) DEFAULT NULL,
  `up_hit_rate` DECIMAL(8,6) DEFAULT NULL, `down_hit_rate` DECIMAL(8,6) DEFAULT NULL,
  `coverage` DECIMAL(8,6) DEFAULT NULL, `ic` DECIMAL(10,6) DEFAULT NULL,
  `avg_ret_up` DECIMAL(10,6) DEFAULT NULL, `avg_ret_down` DECIMAL(10,6) DEFAULT NULL,
  `spread` DECIMAL(10,6) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_metric` (`as_of_date`, `horizon`, `scope_type`, `scope_value`, `window_days`, `is_replay`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板预汇总指标';

CREATE TABLE IF NOT EXISTS `ga_stock_pool` (
  `code` VARCHAR(10) NOT NULL, `market` CHAR(2) NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `pool_status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
  `reason_code` VARCHAR(32) DEFAULT NULL,
  `reason_detail` VARCHAR(255) DEFAULT NULL,
  `since_date` DATE DEFAULT NULL,
  `hit_rate_60` DECIMAL(8,6) DEFAULT NULL, `n_verified_60` INT DEFAULT NULL,
  `shadow_hit_rate_20` DECIMAL(8,6) DEFAULT NULL, `shadow_n_20` INT DEFAULT NULL,
  `last_eval_date` DATE DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`, `market`, `adjustflag`),
  KEY `idx_ga_pool_status` (`pool_status`, `reason_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票池状态与滚动命中率';

CREATE TABLE IF NOT EXISTS `ga_stock_elimination_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(10) NOT NULL, `market` CHAR(2) NOT NULL,
  `adjustflag` TINYINT NOT NULL DEFAULT 2,
  `event_type` VARCHAR(16) NOT NULL,
  `event_date` DATE NOT NULL,
  `reason_code` VARCHAR(32) DEFAULT NULL,
  `reason_detail` VARCHAR(255) DEFAULT NULL,
  `metric_json` JSON DEFAULT NULL,
  `operator` VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ga_elim_code` (`code`, `market`, `event_date`),
  KEY `idx_ga_elim_date` (`event_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='淘汰/复活事件日志，追加式不修改';

CREATE TABLE IF NOT EXISTS `ga_factor_ic_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `as_of_date` DATE NOT NULL,
  `horizon` SMALLINT NOT NULL,
  `feature_name` VARCHAR(64) NOT NULL,
  `ic_20` DECIMAL(10,6) DEFAULT NULL, `ic_60` DECIMAL(10,6) DEFAULT NULL,
  `icir_60` DECIMAL(10,6) DEFAULT NULL,
  `decay_flag` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ga_fic` (`as_of_date`, `horizon`, `feature_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='因子滚动 IC 体检';

CREATE TABLE IF NOT EXISTS `ga_job_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `job_name` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `message` VARCHAR(1024) DEFAULT NULL,
  `processed` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL, `finished_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ga_job` (`job_name`, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行日志与互斥状态';

CREATE TABLE IF NOT EXISTS `ga_cleanup_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `mode` VARCHAR(16) NOT NULL COMMENT 'repair / purge / all',
  `dry_run` TINYINT NOT NULL DEFAULT 1 COMMENT '1 仅预览不写库',
  `detail` VARCHAR(2000) DEFAULT NULL,
  `processed` INT NOT NULL DEFAULT 0,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ga_cleanup_time` (`id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据清理审计日志（只追加，不随清理删除）';
