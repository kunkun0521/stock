package com.li.stock.service;

import com.li.stock.domain.vo.ScoreBuildVO;
import com.li.stock.domain.vo.ScoreScheduleRunVO;
import com.li.stock.repository.ScoreScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 量化评分自动计算的编排层：按交易日逐个复用页面「实时计算评分」的同一套算法。
 *
 * <p>写入是 {@code ON DUPLICATE KEY UPDATE}，重复重算同一交易日只会更新不会新增，
 * 因此启动补跑与定时任务可以安全地重叠；单个交易日失败不影响其余日期。</p>
 */
@Service
public class ScoreScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScoreScheduleService.class);
    private static final int MAX_DAYS = 60;

    private final ScoreCalculationService calculationService;
    private final ScoreScheduleRepository repository;
    /** 启动补跑与定时触发共享互斥，避免两批重算同时抢数据库连接和 CPU。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScoreScheduleService(ScoreCalculationService calculationService,
                                ScoreScheduleRepository repository) {
        this.calculationService = calculationService;
        this.repository = repository;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 重算行情库中最近 days 个交易日（按日期从旧到新），返回批次摘要。
     *
     * @param trigger   触发来源，仅用于日志区分 startup / scheduled
     * @param days      需要重算的交易日数量，限制在 1—60 之间
     * @param adjustFlag 复权口径，非法值回退为默认前复权 2
     */
    public ScoreScheduleRunVO rebuildRecent(String trigger, int days, int adjustFlag) {
        if (!running.compareAndSet(false, true)) {
            log.warn("评分自动计算 [{}] 跳过：上一批重算尚未结束", trigger);
            return ScoreScheduleRunVO.skipped(trigger, adjustFlag);
        }
        long start = System.currentTimeMillis();
        try {
            int limit = Math.max(1, Math.min(days, MAX_DAYS));
            int flag = (adjustFlag >= 1 && adjustFlag <= 3) ? adjustFlag : 2;
            List<LocalDate> dates;
            try {
                dates = new ArrayList<>(repository.recentTradeDates(flag, limit));
            } catch (RuntimeException ex) {
                String message = "查询最近交易日失败：" + rootMessage(ex);
                log.error("评分自动计算 [{}] {}", trigger, message);
                return new ScoreScheduleRunVO(trigger, flag, 0, 0, 1, 0,
                        System.currentTimeMillis() - start, false, List.of(message));
            }
            dates.removeIf(Objects::isNull);
            dates.sort(Comparator.naturalOrder());
            if (dates.isEmpty()) {
                log.warn("评分自动计算 [{}] 跳过：行情表中没有可用交易日（adjustFlag={}）", trigger, flag);
                return new ScoreScheduleRunVO(trigger, flag, 0, 0, 0, 0,
                        System.currentTimeMillis() - start, false, List.of("行情表中没有可用交易日"));
            }

            log.info("评分自动计算 [{}] 开始：最近 {} 个交易日 {} ~ {}，复权口径 {}",
                    trigger, dates.size(), dates.get(0), dates.get(dates.size() - 1), flag);
            int success = 0;
            int failed = 0;
            int savedRows = 0;
            List<String> details = new ArrayList<>();
            for (LocalDate date : dates) {
                try {
                    ScoreBuildVO result = calculationService.rebuild(date, flag);
                    success++;
                    savedRows += result.savedStocks();
                    details.add(date + "：" + result.savedStocks() + " 只 / " + result.elapsedMs() + " ms");
                    log.info("评分自动计算 [{}] {} 完成：{} 只，耗时 {} ms",
                            trigger, date, result.savedStocks(), result.elapsedMs());
                } catch (RuntimeException ex) {
                    failed++;
                    String message = rootMessage(ex);
                    details.add(date + "：失败 - " + message);
                    log.warn("评分自动计算 [{}] {} 失败：{}", trigger, date, message);
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("评分自动计算 [{}] 结束：成功 {} 天，失败 {} 天，写入 {} 条，总耗时 {} ms",
                    trigger, success, failed, savedRows, elapsed);
            return new ScoreScheduleRunVO(trigger, flag, dates.size(), success, failed,
                    savedRows, elapsed, false, details);
        } finally {
            running.set(false);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
