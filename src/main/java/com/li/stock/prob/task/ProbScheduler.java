package com.li.stock.prob.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 收盘后自动跑「一键日常」（文档 §1 目标：每个交易日收盘后自动输出预测、结算、复盘）。
 *
 * <p>默认关闭，在 application.yaml 里把 stock.prob.schedule-enabled 改成 true 即启用；
 * 幂等保护：当天已有一条成功的 PREDICT/DAILY 记录时会跳过，重复启动后端不会重复跑全市场。</p>
 */
@Component
@ConditionalOnProperty(prefix = "stock.prob", name = "schedule-enabled", havingValue = "true")
public class ProbScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProbScheduler.class);
    private final ProbTaskService tasks;
    private final JdbcTemplate jdbc;

    public ProbScheduler(ProbTaskService tasks, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${stock.prob.cron-daily:0 30 18 * * MON-FRI}")
    public void daily() {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM prob_run WHERE run_type IN ('PREDICT','DAILY') "
                            + "AND status='SUCCESS' AND DATE(started_at)=CURDATE()", Long.class);
            if (n != null && n > 0) {
                log.info("[PROB] 今日已完成日常任务，跳过定时触发");
                return;
            }
            Map<String, Object> res = tasks.launch("DAILY", Map.of());
            log.info("[PROB] 定时触发一键日常：runId={}", res.get("runId"));
        } catch (Exception e) {
            log.warn("[PROB] 定时触发失败：{}", e.getMessage());
        }
    }
}
