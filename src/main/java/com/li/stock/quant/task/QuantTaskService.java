package com.li.stock.quant.task;

import com.li.stock.common.BizException;
import com.li.stock.quant.analytics.EventRow;
import com.li.stock.quant.analytics.EventStudy;
import com.li.stock.quant.analytics.Performance;
import com.li.stock.quant.backtest.BacktestConfig;
import com.li.stock.quant.backtest.BacktestEngine;
import com.li.stock.quant.backtest.EquityPoint;
import com.li.stock.quant.backtest.TradeRecord;
import com.li.stock.quant.data.MarketPanel;
import com.li.stock.quant.data.PanelService;
import com.li.stock.quant.strategy.EvalContext;
import com.li.stock.quant.strategy.SignalSeries;
import com.li.stock.quant.strategy.Strategy;
import com.li.stock.quant.strategy.StrategyRegistry;
import com.li.stock.quant.strategy.Universe;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回测任务编排：提交 → 异步执行（加载面板 / 计算信号 / 回测 / 绩效 / 事件研究 / 落库）→ 进度可查。
 *
 * <p>信号计算是整个流程最重的一段（全市场 4606 只 × 491 日），所以回测与事件研究共用
 * 同一份信号结果：一次任务里既出回测指标，也出事件研究，避免重复计算。</p>
 */
@Service
public class QuantTaskService {

    private static final Logger log = LoggerFactory.getLogger(QuantTaskService.class);

    private final PanelService panels;
    private final StrategyRegistry registry;
    private final QuantRepository repo;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "quant-backtest");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, AtomicBoolean> cancels = new ConcurrentHashMap<>();
    private final Map<Long, String> stages = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();

    public QuantTaskService(PanelService panels, StrategyRegistry registry, QuantRepository repo) {
        this.panels = panels;
        this.registry = registry;
        this.repo = repo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            int n = repo.markStaleTasks();
            if (n > 0) log.warn("[QUANT] 启动时清理了 {} 个中断的回测任务", n);
        } catch (Exception e) {
            log.warn("[QUANT] 历史任务状态清理失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    public int runningCount() {
        return running.get();
    }

    // ------------------------------------------------------------------ 提交

    public long submit(String strategyKey, Map<String, Object> rawParams, Map<String, Object> rawConfig) {
        if (strategyKey == null || strategyKey.isBlank()) {
            throw BizException.badRequest("请选择策略");
        }
        Strategy strategy = registry.get(strategyKey);
        Map<String, Object> params = registry.normalize(strategyKey, rawParams);

        MarketPanel panel = panels.peek();
        LocalDate defStart = panel != null ? panel.dateAt(0) : LocalDate.now().minusYears(3);
        LocalDate defEnd = panel != null ? panel.dateAt(panel.dateCount() - 1) : LocalDate.now();
        BacktestConfig cfg = BacktestConfig.from(rawConfig, defStart, defEnd);

        long id = repo.insertTask(strategyKey, strategy.meta().version(),
                repo.toJson(params), repo.toJson(cfg.toMap()), repo.toJson(universeMap(cfg)),
                cfg.start(), cfg.end());
        cancels.put(id, new AtomicBoolean(false));
        stages.put(id, "排队中");
        pool.submit(() -> runTask(id, strategy, params, cfg));
        log.info("[QUANT] 提交回测 #{} {} {}~{}", id, strategyKey, cfg.start(), cfg.end());
        return id;
    }

    public void cancel(long id) {
        AtomicBoolean flag = cancels.get(id);
        if (flag == null) {
            Map<String, Object> task = repo.getTask(id);
            String status = String.valueOf(task.get("status"));
            if (!"RUNNING".equals(status) && !"PENDING".equals(status)) {
                throw BizException.badRequest("任务已结束，无法取消");
            }
            throw BizException.badRequest("任务不在当前实例中运行，无法取消（可能已中断）");
        }
        flag.set(true);
        repo.updateProgress(id, 0, "已请求取消…");
    }

    public Map<String, Object> taskView(long id) {
        Map<String, Object> task = repo.getTask(id);
        task.put("metricsSummary", repo.getMetrics(id, "ALL"));
        return task;
    }

    public Map<String, Object> list(int page, int size, String strategyKey) {
        return repo.listTasks(page, size, strategyKey);
    }

    // ------------------------------------------------------------------ 执行

    private void runTask(long id, Strategy strategy, Map<String, Object> params, BacktestConfig cfg) {
        long t0 = System.currentTimeMillis();
        running.incrementAndGet();
        String snapshot = null;
        try {
            repo.markRunning(id);
            progress(id, 5, "加载行情面板");
            MarketPanel panel = panels.get();
            snapshot = panel.snapshotKey();
            progress(id, 22, "行情面板就绪（" + panel.stockCount() + " 只 / " + panel.dateCount() + " 日）");

            // 日期按面板实际范围收敛，避免用户选了库里没有的日期
            LocalDate start = clamp(panel, cfg.start(), true);
            LocalDate end = clamp(panel, cfg.end(), false);
            if (start.isAfter(end)) throw BizException.badRequest("所选区间不在行情数据范围内");
            BacktestConfig runCfg = BacktestConfig.from(cfg.toMap(), start, end);

            Universe universe = new Universe(panel, runCfg.allowSt(), runCfg.boards(),
                    runCfg.allowNewListing(), runCfg.minAmount20Wan());
            progress(id, 28, "计算指标与入场信号");
            EvalContext ctx = new EvalContext(panel, universe, params, false);
            List<SignalSeries> signals = strategy.evaluate(ctx);
            progress(id, 52, "执行回测撮合");

            int maxHold = intOf(params.get("maxHoldDays"), 0);
            int falseBreak = intOf(params.get("falseBreakDays"), 0);
            BacktestEngine engine = new BacktestEngine();
            BacktestEngine.Outcome outcome = engine.run(panel, runCfg, universe, signals, maxHold, falseBreak,
                    (p, s) -> progress(id, p, s),
                    () -> cancels.getOrDefault(id, new AtomicBoolean(false)).get());

            progress(id, 86, "计算绩效指标");
            Map<String, Object> metricsAll = Performance.compute(outcome.equity(), outcome.trades(),
                    runCfg.rfRate());
            int splitIdx = outcome.equity().isEmpty() ? 0
                    : (int) Math.round(outcome.equity().size() * 0.7);
            List<EquityPoint> isSlice = Performance.slice(outcome.equity(), 0, splitIdx);
            List<EquityPoint> oosSlice = Performance.slice(outcome.equity(), splitIdx,
                    outcome.equity().size() - 1);
            Map<String, Object> metricsIs = Performance.compute(isSlice, outcome.trades(), runCfg.rfRate());
            Map<String, Object> metricsOos = Performance.compute(oosSlice, outcome.trades(), runCfg.rfRate());

            progress(id, 92, "事件研究");
            List<Integer> horizons = new ArrayList<>(strategy.meta().horizons());
            if (horizons.isEmpty()) horizons.addAll(List.of(1, 3, 5, 10, 20));
            List<EventRow> events = EventStudy.run(panel, universe, signals, horizons,
                    runCfg.slippagePct(), runCfg.commissionPct(), runCfg.stampPct(), runCfg.transferPct());

            progress(id, 97, "写入结果");
            repo.saveMetrics(id, "ALL", metricsAll);
            repo.saveMetrics(id, "IS", metricsIs);
            repo.saveMetrics(id, "OOS", metricsOos);
            repo.saveEquity(id, outcome.equity());
            repo.saveTrades(id, outcome.trades());
            repo.saveEvents(id, events);

            Map<String, Object> summary = new LinkedHashMap<>(outcome.summary());
            summary.put("metrics", keyMetrics(metricsAll));
            summary.put("isMetrics", keyMetrics(metricsIs));
            summary.put("oosMetrics", keyMetrics(metricsOos));
            summary.put("eventCount", events.size());
            summary.put("dataSnapshot", snapshot);
            long cost = System.currentTimeMillis() - t0;
            summary.put("durationMs", cost);
            repo.finish(id, "SUCCESS", null, repo.toJson(summary), cost, snapshot, 100, "完成");
            log.info("[QUANT] 回测 #{} 完成，用时 {} ms，交易 {} 笔", id, cost, outcome.trades().size());
        } catch (BacktestEngine.CanceledException e) {
            repo.finish(id, "CANCELED", "用户取消", null, System.currentTimeMillis() - t0, snapshot,
                    0, "已取消");
            log.info("[QUANT] 回测 #{} 已取消", id);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            repo.finish(id, "FAILED", msg.length() > 900 ? msg.substring(0, 900) : msg, null,
                    System.currentTimeMillis() - t0, snapshot, 0, "失败");
            log.warn("[QUANT] 回测 #{} 失败：{}", id, msg, e);
        } finally {
            cancels.remove(id);
            stages.remove(id);
            running.decrementAndGet();
        }
    }

    private void progress(long id, int pct, String stage) {
        stages.put(id, stage);
        try {
            repo.updateProgress(id, Math.min(99, pct), stage);
        } catch (Exception e) {
            log.debug("[QUANT] 进度写入失败：#{} {}", id, e.getMessage());
        }
    }

    private Map<String, Object> keyMetrics(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("totalReturn", "annualReturn", "maxDrawdown", "sharpe", "sortino",
                "calmar", "winRate", "profitFactor", "tradeCount", "excessReturn", "avgHoldDays",
                "turnover", "benchmarkReturn", "isSignificant", "finalNav")) {
            out.put(key, m.get(key));
        }
        return out;
    }

    private Map<String, Object> universeMap(BacktestConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("boards", cfg.boards());
        m.put("allowSt", cfg.allowSt());
        m.put("allowNewListing", cfg.allowNewListing());
        m.put("minAmount20Wan", cfg.minAmount20Wan());
        m.put("marketFilter", cfg.marketFilter());
        return m;
    }

    private LocalDate clamp(MarketPanel panel, LocalDate date, boolean start) {
        if (date == null) return start ? panel.dateAt(0) : panel.dateAt(panel.dateCount() - 1);
        if (date.isBefore(panel.dateAt(0))) return panel.dateAt(0);
        if (date.isAfter(panel.dateAt(panel.dateCount() - 1))) return panel.dateAt(panel.dateCount() - 1);
        return date;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 每日选股

    /** 扫描结果缓存：快照 + 策略 + 参数哈希 → 结果（默认 10 分钟）。 */
    private final Map<String, CachedScan> scanCache = new ConcurrentHashMap<>();

    private record CachedScan(long at, Map<String, Object> data) {
    }

    /**
     * 每日选股：对最新交易日运行指定策略，输出买入候选（含触发明细与历史参考）。
     */
    public Map<String, Object> scan(List<String> strategyKeys, Map<String, Map<String, Object>> paramMap) {
        MarketPanel panel = panels.get();
        int lastIdx = panel.dateCount() - 1;
        LocalDate scanDate = panel.dateAt(lastIdx);
        List<String> keys = (strategyKeys == null || strategyKeys.isEmpty())
                ? registry.all().stream().map(s -> s.meta().key()).toList()
                : strategyKeys;

        Map<String, Object> normalized = new LinkedHashMap<>();
        StringBuilder hashSrc = new StringBuilder(panel.snapshotKey());
        for (String key : keys) {
            Map<String, Object> params = registry.normalize(key, paramMap == null ? null : paramMap.get(key));
            normalized.put(key, params);
            hashSrc.append('|').append(key).append(':').append(repo.toJson(params));
        }
        String cacheKey = md5(hashSrc.toString());
        CachedScan cached = scanCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.at < 10 * 60_000L) {
            return cached.data;
        }

        Universe universe = new Universe(panel, false, java.util.Set.copyOf(BacktestConfig.BOARD_OPTIONS),
                false, 0);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (String key : keys) {
            Strategy strategy = registry.get(key);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) normalized.get(key);
            EvalContext ctx = new EvalContext(panel, universe, params, true);
            List<SignalSeries> signals = strategy.evaluate(ctx);
            SignalSeries[] byStock = new SignalSeries[panel.stockCount()];
            for (SignalSeries s : signals) byStock[s.stockIdx] = s;

            // 参考统计：同策略同参数下 5/10/20 日的胜率与样本数
            List<Integer> refHorizons = List.of(5, 10, 20);
            List<EventRow> refRows = EventStudy.run(panel, universe, signals, refHorizons, 0.1, 0.025, 0.05, 0.001);
            Map<Integer, EventRow> refAll = new LinkedHashMap<>();
            for (EventRow r : refRows) {
                if ("ALL".equals(r.splitKey())) refAll.put(r.horizon(), r);
            }

            List<Map<String, Object>> dayItems = new ArrayList<>();
            for (int packed : panel.dayRows()[lastIdx]) {
                int si = MarketPanel.stockOf(packed);
                int row = MarketPanel.rowOf(packed);
                SignalSeries sig = byStock[si];
                if (sig == null || !sig.entry[row]) continue;
                MarketPanel.Stock s = panel.stocks()[si];
                if (!universe.rowOk(si, row)) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("strategyKey", key);
                item.put("code", s.code);
                item.put("market", s.market);
                item.put("board", s.board);
                item.put("score", sig.score[row]);
                item.put("closePrice", s.close[row]);
                // 建议止损价：优先用策略给出的绝对价，否则按固定止损百分比从收盘价推算
                float stop = sig.stopPrice[row];
                if (Float.isNaN(stop) && sig.stopPct[row] > 0) {
                    stop = s.close[row] * (1 - sig.stopPct[row] / 100);
                }
                if (!Float.isNaN(stop)) item.put("stopPrice", Math.round(stop * 100) / 100d);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("text", sig.detail == null ? "" : sig.detail[row]);
                item.put("detail", detail);

                Map<String, Object> ref = new LinkedHashMap<>();
                for (int h : refHorizons) {
                    EventRow r = refAll.get(h);
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("cnt", r == null ? 0 : r.cnt());
                    one.put("winRate", r == null ? null : r.winRate());
                    one.put("meanRet", r == null ? null : r.meanRet());
                    ref.put("H" + h, one);
                }
                item.put("ref", ref);

                List<String> risk = new ArrayList<>();
                double limitPct = s.limitPct(row);
                if (s.pct[row] >= limitPct - 2) risk.add("接近涨停");
                if (s.amount[row] < 50_000_000) risk.add("流动性偏低");
                if (s.st[row]) risk.add("ST");
                item.put("risk", risk);
                dayItems.add(item);
            }
            dayItems.sort(Comparator.comparingDouble((Map<String, Object> m) ->
                    ((Number) m.get("score")).doubleValue()).reversed());
            if (dayItems.size() > 50) dayItems = dayItems.subList(0, 50);
            counts.put(key, dayItems.size());
            items.addAll(dayItems);
            repo.saveSignals(scanDate, key, cacheKey.length() > 32 ? cacheKey.substring(0, 32) : cacheKey,
                    dayItems);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanDate", String.valueOf(scanDate));
        data.put("snapshot", panel.snapshotKey());
        data.put("counts", counts);
        data.put("items", items);
        data.put("params", normalized);
        data.put("generatedAt", System.currentTimeMillis());
        data.put("note", "信号仅基于历史统计，不构成投资建议");
        scanCache.put(cacheKey, new CachedScan(System.currentTimeMillis(), data));
        if (scanCache.size() > 20) scanCache.clear();
        return data;
    }

    public List<Map<String, Object>> storedSignals(LocalDate date, String strategyKey) {
        return repo.querySignals(date, strategyKey);
    }

    public List<Map<String, Object>> latestBacktests() {
        return repo.latestBacktests();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
