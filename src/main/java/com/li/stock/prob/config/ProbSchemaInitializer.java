package com.li.stock.prob.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * prob_* 建表初始化（文档 §9）。
 *
 * <p>与 Python 引擎的 engine/schema.sql 完全同源，全部 CREATE TABLE IF NOT EXISTS，
 * 应用就绪后幂等执行。用户即使只启动后端看页面、还没跑过引擎，接口也不会因表不存在而 500。
 * 建表失败只告警，不阻断启动。</p>
 */
@Component
@Order(1)
public class ProbSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(ProbSchemaInitializer.class);
    private final DataSource dataSource;

    public ProbSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ClassPathResource("prob_schema.sql"));
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            log.info("[PROB] 建表脚本 prob_schema.sql 已幂等执行");
            ensureIndexes();
        } catch (Exception e) {
            log.warn("[PROB] prob_schema.sql 执行失败，概率引擎接口可能不可用：{}", e.getMessage());
        }
    }

    /**
     * 幂等补覆盖索引：MySQL 不支持 CREATE INDEX IF NOT EXISTS，先查 information_schema。
     * 这两个索引让总览聚合与样本外筛选完全走索引，避免回表读取体积很大的 components_json。
     */
    private void ensureIndexes() throws Exception {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            addIndexIfMissing(st, "idx_prob_pred_ov",
                    "ALTER TABLE prob_prediction ADD INDEX idx_prob_pred_ov "
                            + "(asof_date,pred_type,risk_level,reliability_grade,p_up,code)");
            addIndexIfMissing(st, "idx_prob_pred_oos",
                    "ALTER TABLE prob_prediction ADD INDEX idx_prob_pred_oos "
                            + "(pred_type,status,horizon,asof_date)");
        }
    }

    private void addIndexIfMissing(java.sql.Statement st, String name, String ddl) throws Exception {
        try (var rs = st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='prob_prediction' AND INDEX_NAME='" + name + "'")) {
            if (rs.next() && rs.getInt(1) == 0) {
                st.executeUpdate(ddl);
                log.info("[PROB] 已创建覆盖索引 {}", name);
            }
        }
    }
}
