package com.li.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.common.BizException;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlerDateRangeVO;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.integration.BaoStockClient;
import com.li.stock.repository.CrawlTaskRepository;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 页面触发的 BaoStock 日线增量抓取任务。
 *
 * <p>流程与 Python data/main.py 保持一致：先取股票列表，再按代码前缀筛选，
 * 按股票和复权口径查询数据库日期范围，只请求缺失的历史前段或最新后段，按 00、30、60 代码前缀
 * 分别批量 upsert 到 stock_daily_00、stock_daily_30、stock_daily_60。
 * 任务在后台执行，前端通过 crawl_log 轮询进度，不会让 HTTP 请求长时间阻塞。</p>
 */
@Service
public class CrawlTaskService {
    private static final int DEFAULT_DAYS_BACK = 548;
    private static final int MAX_DAYS_BACK = 3650;
    private static final int DEFAULT_DELAY_MILLIS = 1500;
    private static final int MAX_DELAY_MILLIS = 10000;
    private static final int MAX_STOCK_LIMIT = 5000;

    private final CrawlTaskRepository taskRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockDailyRepository stockDailyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stock-crawler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CrawlTaskService(CrawlTaskRepository taskRepository,
                            StockInfoRepository stockInfoRepository,
                            StockDailyRepository stockDailyRepository) {
        this.taskRepository = taskRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
    }

    public CrawlTaskVO start(CrawlTaskRequest request) {
        if (!running.compareAndSet(false, true)) {
            throw BizException.badRequest("已有数据抓取任务正在运行，请先等待当前任务完成");
        }
        CrawlConfig config = normalize(request);
        CrawlTask task = new CrawlTask();
        task.setTaskName("daily_kline");
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setStartTime(LocalDateTime.now());
        task.setStatus("running");
        task.setRemark("等待连接 BaoStock");
        try {
            taskRepository.create(task);
            executor.submit(() -> execute(task.getId(), config));
            return taskRepository.find(task.getId());
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    public CrawlTaskVO find(long id) {
        CrawlTaskVO task = taskRepository.find(id);
        if (task == null) throw BizException.notFound("抓取任务不存在：" + id);
        return task;
    }

    public List<CrawlTaskVO> recent(Integer limit) {
        int actualLimit = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
        return taskRepository.recent(actualLimit);
    }

    private void execute(long taskId, CrawlConfig config) {
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        try (BaoStockClient client = new BaoStockClient(objectMapper, 10000, 30000)) {
            LocalDate today = LocalDate.now();
            boolean loggedIn = false;
            List<StockInfoVO> stocks = config.refreshStockList()
                    ? List.of()
                    : stockInfoRepository.crawlerStocks(config.prefixes(), config.limit());
            boolean localPoolSatisfiesRequest = !stocks.isEmpty()
                    && (config.limit() == 0 || stocks.size() >= config.limit());
            if (!localPoolSatisfiesRequest) {
                client.login();
                loggedIn = true;
                List<Map<String, String>> sourceStocks = queryLatestStockList(client, today);
                stocks = filterStocks(sourceStocks, config.prefixes());
                if (config.limit() > 0 && stocks.size() > config.limit()) {
                    stocks = new ArrayList<>(stocks.subList(0, config.limit()));
                }
                stockInfoRepository.upsertCrawlerStocks(stocks);
            }
            if (stocks.isEmpty()) throw new IllegalStateException("BaoStock 没有返回符合条件的股票列表");

            taskRepository.updateProgress(taskId, stocks.size(), 0, 0,
                    (localPoolSatisfiesRequest ? "复用本地股票池" : "同步 BaoStock 股票列表")
                            + "，共 " + stocks.size() + " 只，开始增量抓取日线");

            LocalDate initialStart = today.minusDays(config.daysBack());
            Map<String, CrawlerDateRangeVO> dateRanges = stockDailyRepository.crawlerDateRanges(
                    stocks.stream().map(StockInfoVO::code).toList(), config.fqTypes());
            if (!loggedIn && stocks.stream().anyMatch(stock -> needsHistory(stock, today, initialStart, config, dateRanges))) {
                client.login();
            }
            for (int index = 0; index < stocks.size(); index++) {
                StockInfoVO stock = stocks.get(index);
                try {
                    CrawlResult result = crawlOneStock(client, stock, initialStart, today, config, dateRanges);
                    success++;
                    String action = result.requestCount() == 0
                            ? "已是最新，跳过 BaoStock 请求"
                            : "写入 " + result.savedRows() + " 条";
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "："
                                    + stock.code() + "，" + action, false);
                    // 已经是最新的数据不再等待，重复执行时可以快速完成。
                    if (result.requestCount() > 0 && index < stocks.size() - 1) {
                        sleep(config.delayMillis());
                    }
                } catch (Exception ex) {
                    fail++;
                    String message = stock.code() + "：" + rootMessage(ex);
                    errors.add(message);
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "，最近失败：" + message, true);
                }
            }
            String remark = "抓取完成，成功 " + success + " 只，失败 " + fail + " 只";
            if (!errors.isEmpty()) remark += "；" + String.join("；", errors);
            taskRepository.finish(taskId, "done", success, fail, limitText(remark));
        } catch (Exception ex) {
            taskRepository.finish(taskId, "failed", success, fail,
                    limitText("任务失败：" + rootMessage(ex)));
        } finally {
            running.set(false);
        }
    }

    private boolean needsHistory(StockInfoVO stock, LocalDate today, LocalDate initialStart,
                                 CrawlConfig config, Map<String, CrawlerDateRangeVO> dateRanges) {
        return config.fqTypes().stream().anyMatch(fqType -> {
            int adjustFlag = adjustFlagOf(fqType);
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            return range == null || range.firstDate().isAfter(initialStart)
                    || range.latestDate().isBefore(today);
        });
    }

    private CrawlResult crawlOneStock(BaoStockClient client, StockInfoVO stock, LocalDate initialStart,
                                      LocalDate today, CrawlConfig config,
                                      Map<String, CrawlerDateRangeVO> dateRanges) {
        List<StockDailyVO> rows = new ArrayList<>();
        int requestCount = 0;
        for (int fqType : config.fqTypes()) {
            int adjustFlag = adjustFlagOf(fqType);
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
        return new CrawlResult(stockDailyRepository.saveCrawlerBatch(rows), requestCount);
    }

    private List<StockDailyVO> queryHistory(BaoStockClient client, StockInfoVO stock,
                                            LocalDate from, LocalDate to, int adjustFlag) {
        if (from.isAfter(to)) return List.of();
        return toDailyRows(client.queryHistory(stock.secid(), from, to, String.valueOf(adjustFlag)),
                stock.code(), stock.exchange(), adjustFlag);
    }

    /** 每 20 只股票更新一次进度，失败和最后一只立即更新，减少无效数据库写入。 */
    private void updateProgressIfNeeded(long taskId, int total, int index, int success, int fail,
                                        String remark, boolean immediate) {
        if (immediate || index == total - 1 || (index + 1) % 20 == 0) {
            taskRepository.updateProgress(taskId, total, success, fail, remark);
        }
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

    private List<StockInfoVO> filterStocks(List<Map<String, String>> sourceRows, List<String> prefixes) {
        Map<String, StockInfoVO> unique = new LinkedHashMap<>();
        for (Map<String, String> source : sourceRows) {
            String rawCode = text(source, "code");
            String code = rawCode.contains(".") ? rawCode.substring(rawCode.indexOf('.') + 1) : rawCode;
            String exchange = rawCode.toLowerCase().startsWith("sh.") ? "SH" : "SZ";
            if (code.isBlank() || !code.chars().allMatch(Character::isDigit)
                    || prefixes.stream().noneMatch(code::startsWith)) continue;
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
                    ? high.subtract(low).divide(preclose, 8, java.math.RoundingMode.HALF_UP)
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

    private CrawlConfig normalize(CrawlTaskRequest request) {
        int limit = request == null || request.limit() == null ? 0 : request.limit();
        if (limit < 0 || limit > MAX_STOCK_LIMIT) throw BizException.badRequest("limit 必须在 0 到 5000 之间");
        int daysBack = request == null || request.daysBack() == null ? DEFAULT_DAYS_BACK : request.daysBack();
        if (daysBack < 1 || daysBack > MAX_DAYS_BACK) throw BizException.badRequest("daysBack 必须在 1 到 3650 之间");
        int fqType = request == null || request.fqType() == null ? 1 : request.fqType();
        if (fqType < 0 || fqType > 2) throw BizException.badRequest("fqType 必须是 0、1 或 2");
        int delay = request == null || request.requestDelayMillis() == null
                ? DEFAULT_DELAY_MILLIS : request.requestDelayMillis();
        if (delay < 0 || delay > MAX_DELAY_MILLIS) throw BizException.badRequest("请求间隔必须在 0 到 10000 毫秒之间");
        boolean refreshStockList = request != null && Boolean.TRUE.equals(request.refreshStockList());
        boolean raw = request == null || request.storeRawPrices() == null || request.storeRawPrices();
        String prefixText = request == null ? null : request.codePrefixes();
        List<String> prefixes = Arrays.stream((prefixText == null || prefixText.isBlank() ? "00" : prefixText).split(","))
                .map(String::trim).filter(s -> s.matches("\\d{1,6}")).distinct().toList();
        if (prefixes.isEmpty()) throw BizException.badRequest("codePrefixes 至少填写一个数字前缀，例如 00,30,60");
        if (prefixes.stream().anyMatch(prefix -> !(prefix.startsWith("00")
                || prefix.startsWith("30") || prefix.startsWith("60")))) {
            throw BizException.badRequest("当前分表只支持 00、30、60 前缀");
        }
        List<Integer> fqTypes = new ArrayList<>();
        fqTypes.add(fqType);
        if (raw && fqType != 0) fqTypes.add(0);
        return new CrawlConfig(limit, daysBack, fqTypes, prefixes, delay, refreshStockList);
    }

    private int adjustFlagOf(int fqType) {
        return switch (fqType) {
            case 0 -> 3; // 不复权
            case 1 -> 2; // 前复权
            case 2 -> 1; // 后复权
            default -> throw new IllegalArgumentException("不支持的复权类型：" + fqType);
        };
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
            throw new IllegalStateException("抓取任务被中断", ex);
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

    private record CrawlConfig(int limit, int daysBack, List<Integer> fqTypes,
                               List<String> prefixes, int delayMillis, boolean refreshStockList) {}

    private record CrawlResult(int savedRows, int requestCount) {}
}
