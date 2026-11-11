package com.li.stock.schedule;

import com.li.stock.service.ScoreScheduleService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 量化评分自动计算任务，与页面手动「实时计算评分」完全独立：
 * 使用自己的守护线程与互斥锁，手动触发不受影响。
 *
 * <ul>
 *   <li>应用启动完成后：后台异步重算行情库中最近 10 个交易日（默认），补齐历史评分；</li>
 *   <li>每日 21:30（Asia/Shanghai，晚于日线抓取的 17:00 / 18:50 / 20:20）：重算最新交易日。</li>
 * </ul>
 *
 * <p>重算使用与页面相同的 {@code ScoreCalculationService#rebuild}，写入为幂等 upsert，
 * 已在后台线程串行执行，不阻塞应用启动与接口请求。全部开关与天数在
 * {@code stock.score-schedule.*} 配置。</p>
 */
@Component
public class ScoreScheduledTask {

    private static final Logger log = LoggerFactory.getLogger(ScoreScheduledTask.class);

    private final ScoreScheduleService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stock-score-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /** 总开关：置为 false 后启动补跑和定时任务都不再执行。 */
    @Value("${stock.score-schedule.enabled:true}")
    private boolean enabled;
    /** 复权口径，与页面默认一致：2 = 前复权。 */
    @Value("${stock.score-schedule.adjust-flag:2}")
    private int adjustFlag;
    /** 启动补跑开关。 */
    @Value("${stock.score-schedule.startup-enabled:true}")
    private boolean startupEnabled;
    /** 启动时重算的交易日数量，默认 10。 */
    @Value("${stock.score-schedule.startup-days:10}")
    private int startupDays;
    /** 启动后延迟执行，避开缓存预热等启动阶段的数据库压力。 */
    @Value("${stock.score-schedule.startup-delay-millis:3000}")
    private long startupDelayMillis;
    /** 每日定时任务重算的交易日数量，默认只算最新 1 天；漏跑由下次启动补跑兜底。 */
    @Value("${stock.score-schedule.daily-days:1}")
    private int dailyDays;

    public ScoreScheduledTask(ScoreScheduleService service) {
        this.service = service;
    }

    /** 收盘后定时计算：默认每天 21:30，cron 可通过配置覆盖。 */
    @Scheduled(cron = "${stock.score-schedule.cron:0 30 21 * * ?}", zone = "Asia/Shanghai")
    public void scheduledRebuild() {
        if (!enabled) {
            log.info("评分自动计算已停用（stock.score-schedule.enabled=false），跳过定时触发");
            return;
        }
        submit("scheduled", dailyDays, 0);
    }

    /** 启动补跑：应用启动完成即异步重算最近 N 个交易日，不阻塞端口监听。 */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        if (!enabled || !startupEnabled) {
            log.info("评分自动计算启动补跑未启用（enabled={}, startup-enabled={}）", enabled, startupEnabled);
            return;
        }
        log.info("评分自动计算：应用启动完成，将在后台重算最近 {} 个交易日", startupDays);
        submit("startup", startupDays, startupDelayMillis);
    }

    private void submit(String trigger, int days, long delayMillis) {
        if (service.isRunning()) {
            log.warn("评分自动计算 [{}] 跳过：上一批重算尚未结束", trigger);
            return;
        }
        executor.submit(() -> {
            if (delayMillis > 0) sleep(delayMillis);
            // 排队期间可能已有另一批任务开始，服务层还有一层互斥兜底
            service.rebuildRecent(trigger, days, adjustFlag);
        });
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("评分自动计算启动延迟被中断，立即开始执行");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
