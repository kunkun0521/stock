package com.li.stock.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlerDateRangeVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.integration.BaoStockClient;
import com.li.stock.repository.CrawlTaskRepository;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日定时日线抓取任务，与页面触发的 {@code CrawlTaskService} 完全独立：
 * 使用自己的线程和互斥锁，不影响手动抓取功能。
 *
 * <p>三个任务按代码前缀分开执行，错峰避开 BaoStock 限流：
 * <ul>
 *   <li>00 开头（深市主板）：每天 17:00</li>
 *   <li>60 开头（沪市主板）：每天 18:50</li>
 *   <li>30 开头（创业板）：每天 20:20</li>
 * </ul>
 *
 * <p>每次保证覆盖近 500 个交易日：首次执行向前补齐约 730 个自然日
 * （≈500 个交易日，可通过 {@code stock.scheduled-crawl.days-back} 调整），
 * 之后每次只增量补齐数据库缺失的最新后段。复权口径与页面默认一致：
 * 前复权（adjustflag=2）+ 不复权（adjustflag=3）。</p>
 *
 * <p>执行进度同样写入 crawl_log 表（taskName 为 daily_kline_scheduled_XX），
 * 可通过现有的 /api/data-crawler/tasks 接口查看。</p>
 */
@Component
public class DailyKlineScheduledCrawler {
    private static final Logger log = LoggerFactory.getLogger(DailyKlineScheduledCrawler.class);

    /** 前复权 + 不复权，与页面抓取默认口径一致。 */
    private static final List<Integer> ADJUST_FLAGS = List.of(2, 3);

    private final CrawlTaskRepository taskRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockDailyRepository stockDailyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stock-scheduled-crawler");
        thread.setDaemon(true);
        return thread;
    });
    /** 三个任务共享一把锁：上一个前缀没跑完时，新的定时触发直接跳过，避免并发打满 BaoStock。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 总开关，置为 false 后三个定时任务都不再执行。 */
    @Value("${stock.scheduled-crawl.enabled:true}")
    private boolean enabled;
    /** 覆盖窗口（自然日），默认 730 天 ≈ 500 个交易日。 */
    @Value("${stock.scheduled-crawl.days-back:730}")
    private int daysBack;
    /** 每只股票请求之间的间隔，避免触发 BaoStock 限流。 */
    @Value("${stock.scheduled-crawl.delay-millis:1500}")
    private int delayMillis;

    public DailyKlineScheduledCrawler(CrawlTaskRepository taskRepository,
                                      StockInfoRepository stockInfoRepository,
                                      StockDailyRepository stockDailyRepository) {
        this.taskRepository = taskRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
    }

    /** 00 开头（深市主板）：每天 17:00 执行。 */
    @Scheduled(cron = "${stock.scheduled-crawl.cron-00:0 0 17 * * ?}", zone = "Asia/Shanghai")
    public void crawlPrefix00() {
        submit("00");
    }

    /** 60 开头（沪市主板）：每天 18:50 执行。 */
    @Scheduled(cron = "${stock.scheduled-crawl.cron-60:0 50 18 * * ?}", zone = "Asia/Shanghai")
    public void crawlPrefix60() {
        submit("60");
    }

    /** 30 开头（创业板）：每天 20:20 执行。 */
    @Scheduled(cron = "${stock.scheduled-crawl.cron-30:0 20 20 * * ?}", zone = "Asia/Shanghai")
    public void crawlPrefix30() {
        submit("30");
    }

    private void submit(String prefix) {
        if (!enabled) {
            log.info("定时抓取已停用（stock.scheduled-crawl.enabled=false），跳过 {} 前缀任务", prefix);
            return;
        }
        executor.submit(() -> execute(prefix));
    }

    private void execute(String prefix) {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一个定时抓取任务尚未结束，本次 {} 前缀任务跳过", prefix);
            return;
        }
        CrawlTask task = new CrawlTask();
        task.setTaskName("daily_kline_scheduled_" + prefix);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setStartTime(LocalDateTime.now());
        task.setStatus("running");
        task.setRemark("定时任务已启动，等待连接 BaoStock");
        try {
            taskRepository.create(task);
        } catch (RuntimeException ex) {
            running.set(false);
            log.error("定时抓取 {} 前缀：创建任务记录失败：{}", prefix, ex.getMessage());
            return;
        }
        long taskId = task.getId();
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        try (BaoStockClient client = new BaoStockClient(objectMapper, 10000, 60000)) {
            LocalDate today = LocalDate.now();
            List<String> prefixes = List.of(prefix);
            List<StockInfoVO> stocks = stockInfoRepository.crawlerStocks(prefixes, 0);
            if (stocks.isEmpty()) {
                client.login();
                stocks = filterStocks(queryLatestStockList(client, today), prefix);
                stockInfoRepository.upsertCrawlerStocks(stocks);
            }
            if (stocks.isEmpty()) {
                throw new IllegalStateException("BaoStock 没有返回 " + prefix + " 前缀的股票列表");
            }
            log.info("定时抓取 {} 前缀：共 {} 只股票，开始增量抓取日线", prefix, stocks.size());
            taskRepository.updateProgress(taskId, stocks.size(), 0, 0,
                    "定时任务，共 " + stocks.size() + " 只，开始增量抓取日线");

            LocalDate initialStart = today.minusDays(daysBack);
            Map<String, CrawlerDateRangeVO> dateRanges = stockDailyRepository.crawlerDateRanges(
                    stocks.stream().map(StockInfoVO::code).toList(), ADJUST_FLAGS);

            for (int index = 0; index < stocks.size(); index++) {
                StockInfoVO stock = stocks.get(index);
                try {
                    int requests = crawlOneStock(client, stock, initialStart, today, dateRanges);
                    success++;
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "：" + stock.code()
                                    + (requests == 0 ? "，已是最新" : "，写入完成"), false);
                    if (requests > 0 && index < stocks.size() - 1) {
                        sleep(delayMillis);
                    }
                } catch (Exception ex) {
                    fail++;
                    String message = stock.code() + "：" + rootMessage(ex);
                    errors.add(message);
                    log.warn("定时抓取 {} 失败：{}", stock.code(), message);
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "，最近失败：" + message, true);
                }
            }
            String remark = "定时抓取完成，成功 " + success + " 只，失败 " + fail + " 只";
            if (!errors.isEmpty()) remark += "；" + String.join("；", errors);
            taskRepository.finish(taskId, "done", success, fail, limitText(remark));
            log.info("定时抓取 {} 前缀完成：成功 {} 只，失败 {} 只", prefix, success, fail);
        } catch (Exception ex) {
            taskRepository.finish(taskId, "failed", success, fail,
                    limitText("定时任务失败：" + rootMessage(ex)));
            log.error("定时抓取 {} 前缀失败：{}", prefix, rootMessage(ex), ex);
        } finally {
            running.set(false);
        }
    }

    /** 与页面抓取相同的增量策略：无记录补全量，否则补齐前段历史和最新后段。 */
    private int crawlOneStock(BaoStockClient client, StockInfoVO stock, LocalDate initialStart,
                              LocalDate today, Map<String, CrawlerDateRangeVO> dateRanges) {
        List<StockDailyVO> rows = new ArrayList<>();
        int requestCount = 0;
        for (int adjustFlag : ADJUST_FLAGS) {
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            if (range == null) {
                rows.addAll(queryHistory(client, stock, initialStart, today, adjustFlag));
                requestCount++;
                continue;
            }
            if (range.firstDate().isAfter(initialStart)) {
                rows.addAll(queryHistory(client, stock, initialStart,
                        range.firstDate().minusDays(1), adjustFlag));
                requestCount++;
            }
            if (range.latestDate().isBefore(today)) {
                rows.addAll(queryHistory(client, stock, range.latestDate().plusDays(1),
                        today, adjustFlag));
                requestCount++;
            }
        }
        stockDailyRepository.saveCrawlerBatch(rows);
        return requestCount;
    }

    private List<StockDailyVO> queryHistory(BaoStockClient client, StockInfoVO stock,
                                            LocalDate from, LocalDate to, int adjustFlag) {
        if (from.isAfter(to)) return List.of();
        return toDailyRows(client.queryHistory(stock.secid(), from, to, String.valueOf(adjustFlag)),
                stock.code(), stock.exchange(), adjustFlag);
    }

    private List<Map<String, String>> queryLatestStockList(BaoStockClient client, LocalDate today) {
        RuntimeException last = null;
        for (int offset = 0; offset < 40; offset++) {
            try {
                List<Map<String, String>> rows = client.queryAllStock(today.minusDays(offset));
                if (!rows.isEmpty()) return rows;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("最近 40 天均未获取到 BaoStock 股票列表"
                + (last == null ? "" : "：" + rootMessage(last)));
    }

    private List<StockInfoVO> filterStocks(List<Map<String, String>> sourceRows, String prefix) {
        Map<String, StockInfoVO> unique = new LinkedHashMap<>();
        for (Map<String, String> source : sourceRows) {
            String rawCode = text(source, "code");
            String code = rawCode.contains(".") ? rawCode.substring(rawCode.indexOf('.') + 1) : rawCode;
            String exchange = rawCode.toLowerCase().startsWith("sh.") ? "SH" : "SZ";
            if (code.isBlank() || !code.chars().allMatch(Character::isDigit)
                    || !code.startsWith(prefix)) continue;
            if (code.startsWith("60") && !"SH".equals(exchange)) continue;
            if ((code.startsWith("00") || code.startsWith("30")) && !"SZ".equals(exchange)) continue;
            int market = "SH".equals(exchange) ? 1 : 0;
            String board = code.startsWith("30") ? "创业板" : "主板";
            unique.put(code + "|" + exchange, new StockInfoVO(
                    code, text(source, "code_name"), market, exchange.toLowerCase() + "." + code,
                    board, exchange, null, null, null, null, null, null, null, null));
        }
        return new ArrayList<>(unique.values());
    }

    private List<StockDailyVO> toDailyRows(List<Map<String, String>> sourceRows, String code,
                                           String market, int fallbackAdjustFlag) {
        List<StockDailyVO> rows = new ArrayList<>();
        for (Map<String, String> source : sourceRows) {
            BigDecimal close = decimal(source, "close");
            BigDecimal preclose = decimal(source, "preclose");
            BigDecimal high = decimal(source, "high");
            BigDecimal low = decimal(source, "low");
            BigDecimal amplitude = high != null && low != null && preclose != null && preclose.signum() != 0
                    ? high.subtract(low).divide(preclose, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)) : null;
            BigDecimal chgAmt = close != null && preclose != null ? close.subtract(preclose) : null;
            rows.add(new StockDailyVO(
                    code, market, LocalDate.parse(text(source, "date")), decimal(source, "open"), close,
                    high, low, preclose, longValue(source, "volume"), decimal(source, "amount"), amplitude,
                    decimal(source, "pctChg"), chgAmt, decimal(source, "turn"),
                    integerValue(source, "adjustflag", fallbackAdjustFlag), integerValue(source, "tradestatus", null),
                    integerValue(source, "isST", null), decimal(source, "peTTM"), decimal(source, "pbMRQ"),
                    decimal(source, "psTTM"), decimal(source, "pcfNcfTTM"), "baostock"));
        }
        return rows;
    }

    /** 每 20 只股票更新一次进度，失败和最后一只立即更新。 */
    private void updateProgressIfNeeded(long taskId, int total, int index, int success, int fail,
                                        String remark, boolean immediate) {
        if (immediate || index == total - 1 || (index + 1) % 20 == 0) {
            taskRepository.updateProgress(taskId, total, success, fail, remark);
        }
    }

    private String text(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    private BigDecimal decimal(Map<String, String> row, String key) {
        String value = text(row, key);
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(Map<String, String> row, String key) {
        BigDecimal value = decimal(row, key);
        return value == null ? null : value.longValue();
    }

    private Integer integerValue(Map<String, String> row, String key, Integer fallback) {
        String value = text(row, key);
        if (value.isBlank()) return fallback;
        try {
            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void sleep(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("定时抓取任务被中断", ex);
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String limitText(String text) {
        return text.length() <= 60000 ? text : text.substring(0, 60000);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
