package com.li.stock.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.common.MarketCacheWarmer;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 *
 * <p>启动补跑：定时任务只在应用运行期间触发，应用未启动或电脑睡眠会错过执行。
 * 每次启动完成后检查各前缀，今日计划时间已过的立即补跑一次（数据已同步时零请求短路），
 * 可用 {@code stock.scheduled-crawl.catch-up-on-startup=false} 关闭。</p>
 */
@Component
public class DailyKlineScheduledCrawler {
    private static final Logger log = LoggerFactory.getLogger(DailyKlineScheduledCrawler.class);

    /** 前复权 + 不复权，与页面抓取默认口径一致。 */
    private static final List<Integer> ADJUST_FLAGS = List.of(2, 3);

    private final CrawlTaskRepository taskRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockDailyRepository stockDailyRepository;
    private final MarketCacheWarmer marketCacheWarmer;
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
    /** 启动补跑开关：应用启动时把今天已经错过计划时间的前缀补跑一遍。 */
    @Value("${stock.scheduled-crawl.catch-up-on-startup:false}")
    private boolean catchUpOnStartup;
    /** 三个前缀的计划时间，与 @Scheduled 注解上的 cron 保持一致，补跑时从 cron 解析出时分。 */
    @Value("${stock.scheduled-crawl.cron-00:0 0 17 * * ?}")
    private String cron00;
    @Value("${stock.scheduled-crawl.cron-60:0 50 18 * * ?}")
    private String cron60;
    @Value("${stock.scheduled-crawl.cron-30:0 20 20 * * ?}")
    private String cron30;

    public DailyKlineScheduledCrawler(CrawlTaskRepository taskRepository,
                                      StockInfoRepository stockInfoRepository,
                                      StockDailyRepository stockDailyRepository,
                                      MarketCacheWarmer marketCacheWarmer) {
        this.taskRepository = taskRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
        this.marketCacheWarmer = marketCacheWarmer;
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

    /**
     * 启动补跑：@Scheduled 只在应用运行期间触发，应用没开或电脑睡眠就会错过当天的执行。
     * 启动完成后检查每个前缀——今天的计划时间已过就补跑一次；计划时间未到则留给定时任务。
     * 数据已同步的前缀在 execute() 里会零请求短路，因此重复启动没有副作用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!enabled || !catchUpOnStartup) return;
        LocalTime now = LocalTime.now();
        Map<String, String> prefixCrons = new LinkedHashMap<>();
        prefixCrons.put("00", cron00);
        prefixCrons.put("60", cron60);
        prefixCrons.put("30", cron30);
        prefixCrons.forEach((prefix, cron) -> {
            LocalTime planned = scheduledTimeOf(cron);
            if (planned == null) {
                log.warn("定时抓取 {} 前缀：无法从 cron [{}] 解析计划时间，跳过启动补跑", prefix, cron);
                return;
            }
            if (now.isBefore(planned)) {
                log.info("定时抓取 {} 前缀：今日计划时间 {} 未到，等待定时触发", prefix, planned);
                return;
            }
            log.info("定时抓取 {} 前缀：今日计划时间 {} 已过，启动补跑", prefix, planned);
            executor.submit(() -> execute(prefix));
        });
    }

    /** 从六位 cron（秒 分 时 …）中解析每日计划时间，解析失败返回 null。 */
    private LocalTime scheduledTimeOf(String cron) {
        try {
            String[] fields = cron.trim().split("\\s+");
            return LocalTime.of(Integer.parseInt(fields[2]), Integer.parseInt(fields[1]));
        } catch (RuntimeException ex) {
            return null;
        }
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
            LocalDate initialStart = today.minusDays(daysBack);
            Map<String, CrawlerDateRangeVO> dateRanges = stockDailyRepository.crawlerDateRanges(
                    stocks.stream().map(StockInfoVO::code).toList(), ADJUST_FLAGS);

            // 已同步到今日（含历史前段）就整轮跳过，一次 BaoStock 请求都不发；
            // 否则先查一次交易日历拿到最近交易日，避免周末/节假日对每只股票空发请求。
            boolean needsSync = stocks.stream()
                    .anyMatch(stock -> needsSync(stock, today, initialStart, dateRanges));
            if (!needsSync) {
                taskRepository.finish(taskId, "done", 0, 0,
                        "全部股票已同步到今日数据，未调用 BaoStock 接口");
                log.info("定时抓取 {} 前缀：{} 只股票均已同步到今日，跳过接口调用", prefix, stocks.size());
                return;
            }
            LocalDate syncEnd = lastTradeDay(client, today);
            log.info("定时抓取 {} 前缀：共 {} 只股票，同步截止 {}，开始增量抓取日线", prefix, stocks.size(), syncEnd);
            taskRepository.updateProgress(taskId, stocks.size(), 0, 0,
                    "定时任务，共 " + stocks.size() + " 只，同步截止 " + syncEnd + "，开始增量抓取日线");

            for (int index = 0; index < stocks.size(); index++) {
                StockInfoVO stock = stocks.get(index);
                try {
                    int requests = crawlOneStock(client, stock, initialStart, syncEnd, dateRanges);
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
            // 无论成败都刷新看板缓存：成功则把新增行情加载进缓存，失败则避免继续返回抓取期间的半旧快照。
            marketCacheWarmer.refreshAsync();
        }
    }

    /** 数据库缺少历史前段、或最新日期还没到今天时，才需要访问 BaoStock。 */
    private boolean needsSync(StockInfoVO stock, LocalDate today, LocalDate initialStart,
                              Map<String, CrawlerDateRangeVO> dateRanges) {
        return ADJUST_FLAGS.stream().anyMatch(adjustFlag -> {
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            return range == null || range.firstDate().isAfter(initialStart)
                    || range.latestDate().isBefore(today);
        });
    }

    /**
     * 最近一个交易日（不超过今天）。交易日历查询失败时退化为今天，
     * 此时非交易日会对每只股票发出返回空结果的请求，但不影响正确性。
     */
    private LocalDate lastTradeDay(BaoStockClient client, LocalDate today) {
        try {
            List<LocalDate> days = client.queryTradeDates(today.minusDays(31), today);
            if (!days.isEmpty()) return days.get(days.size() - 1);
        } catch (RuntimeException ex) {
            log.warn("查询 BaoStock 交易日历失败，按今天作为同步截止日：{}", rootMessage(ex));
        }
        return today;
    }

    /** 与页面抓取相同的增量策略：无记录补全量，否则补齐前段历史和最新后段。 */
    private int crawlOneStock(BaoStockClient client, StockInfoVO stock, LocalDate initialStart,
                              LocalDate syncEnd, Map<String, CrawlerDateRangeVO> dateRanges) {
        List<StockDailyVO> rows = new ArrayList<>();
        int requestCount = 0;
        for (int adjustFlag : ADJUST_FLAGS) {
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            if (range == null) {
                rows.addAll(queryHistory(client, stock, initialStart, syncEnd, adjustFlag));
                requestCount++;
                continue;
            }
            if (range.firstDate().isAfter(initialStart)) {
                rows.addAll(queryHistory(client, stock, initialStart,
                        range.firstDate().minusDays(1), adjustFlag));
                requestCount++;
            }
            // 最新后段只在数据库还没有最近交易日的数据时才补，已同步的股票不发请求。
            if (range.latestDate().isBefore(syncEnd)) {
                rows.addAll(queryHistory(client, stock, range.latestDate().plusDays(1),
                        syncEnd, adjustFlag));
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
