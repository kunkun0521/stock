package com.li.stock.quant.config;

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
 * 量化平台建表初始化。
 *
 * <p>与 GA 模块同样的做法：单独的 {@code quant_schema.sql} 全是 CREATE TABLE IF NOT EXISTS，
 * 在应用就绪后幂等执行，不改动 {@code score_schema.sql}，也不影响已有表。DDL 失败只告警，
 * 不阻断启动。</p>
 */
@Component
@Order(0)
public class QuantSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(QuantSchemaInitializer.class);

    private final DataSource dataSource;

    public QuantSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ClassPathResource("quant_schema.sql"));
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            log.info("[QUANT] 建表脚本 quant_schema.sql 已幂等执行");
        } catch (Exception e) {
            log.warn("[QUANT] quant_schema.sql 执行失败，量化接口可能不可用：{}", e.getMessage());
        }
    }
}
