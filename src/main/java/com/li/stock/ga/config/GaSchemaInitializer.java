package com.li.stock.ga.config;

import com.li.stock.ga.repository.GaDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

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
        try {
            config.ensureDefaults();
            String warning = data.checkPriceConsistency();
            if (!warning.isEmpty()) log.warn("[GA] {}", warning);
        } catch (Exception e) {
            log.warn("[GA] 配置初始化 / 价格口径检查失败：{}", e.getMessage());
        }
    }
}
