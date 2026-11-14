package com.li.stock.ga.config;

import com.li.stock.ga.repository.GaDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * GA 模块启动初始化（规格 0 / 8）。
 *
 * <p>项目现有的 {@code score_schema.sql} 由 spring.sql.init 在启动阶段执行；GA 的 14 张新表
 * 全部是 CREATE TABLE IF NOT EXISTS，这里在 {@link ApplicationReadyEvent} 之后用
 * {@link ResourceDatabasePopulator} 单独幂等执行，不改动现有初始化脚本，也不与
 * {@code stock_daily_all} 视图的创建顺序耦合。DDL 失败只告警，不阻断整个应用启动。</p>
 *
 * <p>随后补齐 ga_config 缺省参数（不覆盖用户已改的值），并抽样比对两张源表的 close，
 * 偏差超阈值的告警写入看板顶部提示。</p>
 */
@Component
public class GaSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(GaSchemaInitializer.class);

    private final DataSource dataSource;
    private final GaConfig config;
    private final GaDataRepository data;

    public GaSchemaInitializer(DataSource dataSource, GaConfig config, GaDataRepository data) {
        this.dataSource = dataSource;
        this.config = config;
        this.data = data;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("ga_schema.sql"));
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            log.info("[GA] 建表脚本 ga_schema.sql 已幂等执行");
        } catch (Exception e) {
            log.warn("[GA] ga_schema.sql 执行失败，GA 接口可能不可用：{}", e.getMessage());
            return;
        }
        // CREATE TABLE IF NOT EXISTS 对已存在的表不会补新索引；这里幂等补齐
        // 看板/错误分析高频查询所需的二级索引，失败只告警不影响启动。
        ensureIndexes();
        try {
            config.ensureDefaults();
            String warning = data.checkPriceConsistency();
            if (!warning.isEmpty()) log.warn("[GA] {}", warning);
        } catch (Exception e) {
            log.warn("[GA] 配置初始化 / 价格口径检查失败：{}", e.getMessage());
        }
    }

    /**
     * 幂等补索引：老库表已存在，ga_schema.sql 的 CREATE TABLE IF NOT EXISTS 不会命中，
     * 这里按需 ALTER。每个索引先查 information_schema 判断是否已存在，避免重复执行。
     *
     * <p>这些是「覆盖索引」：把看板/错误分析高频聚合需要的列一并纳入二级索引，
     * 让 COUNT/SUM/AVG 纯走索引不回表。ga_prediction 已 259 万行，旧索引
     * trade_date 或 horizon 打头导致 36~48 万行回表，overview/analysis 一度 5~8 秒，
     * 加覆盖索引后降到数百毫秒（约 20 倍）。</p>
     */
    private void ensureIndexes() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // overview 主聚合：status+model_role+in_pool+is_replay 等值 + trade_date 范围，
        // 聚合列 is_correct/pred_direction/confidence 一并覆盖，避免 36 万行回表。
        addIndexIfMissing(jdbc, "ga_prediction", "idx_ga_pred_ov",
                "(`status`, `model_role`, `in_pool`, `is_replay`, `trade_date`, `is_correct`, `pred_direction`, `confidence`)");
        // 错误分析 confusion：status+horizon+trade_date 过滤，pred_direction/actual_return 分组列覆盖。
        addIndexIfMissing(jdbc, "ga_prediction", "idx_ga_pred_conf",
                "(`status`, `horizon`, `trade_date`, `pred_direction`, `actual_return`)");
        // 错误分析 buckets（默认按 vol_20 分箱）：vol_20 分组列 + is_correct 聚合列覆盖。
        addIndexIfMissing(jdbc, "ga_prediction", "idx_ga_pred_bkt",
                "(`status`, `horizon`, `trade_date`, `pred_direction`, `vol_20`, `is_correct`)");
        // 错误分析 reliability / quantile：prob_up / actual_return 覆盖，分箱在 JVM 内完成。
        addIndexIfMissing(jdbc, "ga_prediction", "idx_ga_pred_rel",
                "(`status`, `horizon`, `trade_date`, `prob_up`, `actual_return`)");
    }

    private void addIndexIfMissing(JdbcTemplate jdbc, String table, String index, String cols) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    Integer.class, table, index);
            if (n != null && n > 0) return;
            jdbc.execute("ALTER TABLE `" + table + "` ADD KEY `" + index + "` " + cols);
            log.info("[GA] 已为 {} 补建索引 {} {}", table, index, cols);
        } catch (Exception e) {
            log.warn("[GA] 补建索引 {}.{} 失败：{}", table, index, e.getMessage());
        }
    }
}
