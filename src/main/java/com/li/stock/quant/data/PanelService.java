package com.li.stock.quant.data;

import com.li.stock.common.BizException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行情面板服务：加载 + 缓存 + 加载状态。
 *
 * <p>性能是这里的核心约束：全市场 155 万行、4606 只股票。做法是</p>
 * <ol>
 *   <li>按分表 00/30/60 分别 {@code ORDER BY code, trade_date} 顺序扫描（走
 *       {@code idx_daily_resume}），避免对 UNION 视图做 filesort；</li>
 *   <li>JDBC 流式读取（fetchSize = Integer.MIN_VALUE），任何时刻只驻留一只股票的缓冲；</li>
 *   <li>面板构建完按快照缓存（默认 30 分钟），后续回测/事件研究/选股直接复用，
 *       单个请求只做纯内存计算。</li>
 * </ol>
 *
 * <p>首次加载约数秒；之后同一快照内所有接口都是毫秒级。</p>
 */
@Service
public class PanelService {

    private static final Logger log = LoggerFactory.getLogger(PanelService.class);

    /** 三张分表按代码前缀拆分，拼起来仍是全局按 code 升序。 */
    private static final String[] SHARDS = {"stock_daily_00", "stock_daily_30", "stock_daily_60"};

    private static final String BASE_SQL = """
            SELECT code, market, trade_date, open, high, low, close, preclose,
                   volume, amount, pct_chg, tradestatus, is_st, pe_ttm, pb_mrq
            FROM %s
            WHERE adjustflag = 2
            ORDER BY code, trade_date
            """;

    private final JdbcTemplate jdbc;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "quant-panel-loader");
        t.setDaemon(true);
        return t;
    });

    @Value("${quant.panel-ttl-minutes:30}")
    private int ttlMinutes;

    @Value("${quant.panel-max-stocks:8000}")
    private int maxStocks;

    private volatile MarketPanel current;
    private volatile long expiresAt;
    private volatile CompletableFuture<MarketPanel> loading;
    private volatile int progress;
    private volatile String message = "未加载";

    private final Object lock = new Object();

    public PanelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PreDestroy
    public void shutdown() {
        loader.shutdownNow();
    }

    /** 取面板；未加载或已过期时阻塞加载（最多 3 分钟）。 */
    public MarketPanel get() {
        MarketPanel p = current;
        if (p != null && System.currentTimeMillis() < expiresAt) return p;
        try {
            return startLoad().get(180, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "行情面板加载超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "行情面板加载被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "行情面板加载失败：" + cause.getMessage());
        }
    }

    /** 已加载且未过期时直接返回，否则返回 null（用于状态接口，不触发加载）。 */
    public MarketPanel peek() {
        MarketPanel p = current;
        return p != null && System.currentTimeMillis() < expiresAt ? p : null;
    }

    /** 异步触发加载，立即返回 future（状态接口用）。 */
    public CompletableFuture<MarketPanel> startLoad() {
        synchronized (lock) {
            if (current != null && System.currentTimeMillis() < expiresAt) {
                return CompletableFuture.completedFuture(current);
            }
            if (loading != null && !loading.isDone()) return loading;
            progress = 0;
            message = "准备加载行情面板";
            loading = CompletableFuture.supplyAsync(this::load, loader);
            return loading;
        }
    }

    /** 强制作废缓存，下次请求重新加载。 */
    public void invalidate() {
        synchronized (lock) {
            current = null;
            expiresAt = 0;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        MarketPanel p = peek();
        m.put("loaded", p != null);
        m.put("loading", loading != null && !loading.isDone());
        m.put("progress", progress);
        m.put("message", message);
        if (p != null) {
            m.put("snapshot", p.snapshotKey());
            m.put("rowCount", p.rowCount());
            m.put("stockCount", p.stockCount());
            m.put("dateCount", p.dateCount());
            m.put("dateFrom", String.valueOf(p.dateAt(0)));
            m.put("dateTo", String.valueOf(p.dateAt(p.dateCount() - 1)));
            m.put("loadedAt", p.loadedAt());
            m.put("loadCostMs", p.loadCostMs());
            m.put("expiresInSec", Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000));
            m.put("quality", p.quality().toMap());
        }
        return m;
    }

    // ------------------------------------------------------------------ 加载

    private MarketPanel load() {
        long t0 = System.currentTimeMillis();
        long rows = 0;
        int badRows = 0;
        int suspendedRows = 0;
        int stRows = 0;
        int dropped = 0;

        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        List<LocalDate> dateOrder = new ArrayList<>();
        List<MarketPanel.Stock> stocks = new ArrayList<>(5200);
        List<Double> missingRatios = new ArrayList<>(5200);

        for (String shard : SHARDS) {
            rows += loadShard(shard, dateIndex, dateOrder, stocks, missingRatios, t0);
        }

        // 日期轴排序 + 把每只股票的临时日期下标重映射为最终下标
        List<LocalDate> sorted = new ArrayList<>(dateOrder);
        Collections.sort(sorted);
        int[] remap = new int[dateOrder.size()];
        for (int i = 0; i < sorted.size(); i++) {
            remap[dateIndex.get(sorted.get(i))] = i;
        }
        LocalDate[] dates = sorted.toArray(new LocalDate[0]);

        for (MarketPanel.Stock s : stocks) {
            for (int i = 0; i < s.day.length; i++) s.day[i] = remap[s.day[i]];
            if (!s.qualityOk) {
                dropped++;
                continue;
            }
            for (int i = 0; i < s.rows(); i++) {
                if (!s.tradable[i]) {
                    suspendedRows++;
                }
                if (s.st[i]) {
                    stRows++;
                }
                if (s.high[i] < s.low[i] - 1e-4
                        || s.close[i] > s.high[i] + 1e-4
                        || s.close[i] < s.low[i] - 1e-4) {
                    badRows++;
                }
            }
        }

        MarketPanel.Stock[] arr = stocks.toArray(MarketPanel.Stock[]::new);
        int[][] dayRows = buildDayRows(arr, dates.length);
        double[] benchmark = buildBenchmark(arr, dayRows, dates.length);

        long costMs = System.currentTimeMillis() - t0;
        double avgMissing = missingRatios.isEmpty() ? 0
                : missingRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int kept = arr.length - dropped;
        MarketPanel.Quality quality = new MarketPanel.Quality(
                kept, dropped, badRows, suspendedRows, stRows, avgMissing, costMs);
        String snapshotKey = "R" + rows + "-D" + dates[dates.length - 1];
        message = "就绪";
        progress = 100;
        current = new MarketPanel(snapshotKey, dates, arr, dayRows, rows, System.currentTimeMillis(),
                costMs, benchmark, quality);
        expiresAt = System.currentTimeMillis() + Math.max(1, ttlMinutes) * 60_000L;
        saveSnapshot(current);
        log.info("[QUANT] 行情面板加载完成 {} 行 / {} 只 / {} 个交易日，耗时 {} ms",
                rows, kept, dates.length, costMs);
        return current;
    }

    /** 读取一张分表；返回本次读取的行数。 */
    private long loadShard(String shard, Map<LocalDate, Integer> dateIndex, List<LocalDate> dateOrder,
                           List<MarketPanel.Stock> stocks, List<Double> missingRatios, long t0) {
        String sql = String.format(BASE_SQL, shard);
        AtomicLong counter = new AtomicLong();
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    String curCode = null;
                    String curMarket = null;
                    Builder b = null;
                    while (rs.next()) {
                        String code = rs.getString(1);
                        String market = rs.getString(2);
                        LocalDate date = rs.getDate(3).toLocalDate();
                        Integer di = dateIndex.get(date);
                        if (di == null) {
                            di = dateOrder.size();
                            dateIndex.put(date, di);
                            dateOrder.add(date);
                        }
                        if (!code.equals(curCode) || !market.equals(curMarket)) {
                            if (b != null) addStock(b, stocks, missingRatios);
                            b = new Builder(code, market);
                            curCode = code;
                            curMarket = market;
                        }
                        b.add(rs, di);
                        long n = counter.incrementAndGet();
                        if ((n & 0xFFFF) == 0) {
                            long elapsed = System.currentTimeMillis() - t0;
                            progress = Math.min(95, (int) Math.min(95, elapsed / 60));
                            message = "正在读取行情 " + (n / 10000) + " 万行（" + shard + "）";
                        }
                        if (stocks.size() >= maxStocks) {
                            break;
                        }
                    }
                    if (b != null) addStock(b, stocks, missingRatios);
                }
            }
            return counter.get();
        });
    }

    private void addStock(Builder b, List<MarketPanel.Stock> stocks, List<Double> missingRatios) {
        int n = b.size;
        if (n == 0) return;
        int[] day = new int[n];
        float[] open = new float[n], high = new float[n], low = new float[n], close = new float[n];
        float[] preclose = new float[n], pct = new float[n], volume = new float[n], amount = new float[n];
        float[] pe = new float[n], pb = new float[n];
        boolean[] tradable = new boolean[n], st = new boolean[n];
        int bad = 0;
        for (int i = 0; i < n; i++) {
            day[i] = b.day[i];
            open[i] = b.open[i];
            high[i] = b.high[i];
            low[i] = b.low[i];
            close[i] = b.close[i];
            preclose[i] = b.preclose[i];
            pct[i] = b.pct[i];
            volume[i] = b.volume[i];
            amount[i] = b.amount[i];
            pe[i] = b.pe[i];
            pb[i] = b.pb[i];
            tradable[i] = b.tradable[i];
            st[i] = b.st[i];
            if (high[i] < low[i] - 1e-4 || close[i] > high[i] + 1e-4 || close[i] < low[i] - 1e-4) bad++;
            if (preclose[i] > 0 && Math.abs(pct[i] - (close[i] / preclose[i] - 1) * 100) > 0.5) bad++;
        }
        int span = day[n - 1] - day[0] + 1;
        double missing = span > 0 ? 1 - (double) n / span : 0;
        missingRatios.add(missing);
        boolean qualityOk = n >= 60 && (double) bad / n <= 0.05;
        stocks.add(new MarketPanel.Stock(stocks.size(), b.code, b.market, day, open, high, low, close,
                preclose, pct, volume, amount, pe, pb, tradable, st, qualityOk));
    }

    private int[][] buildDayRows(MarketPanel.Stock[] stocks, int dateCount) {
        int[] counts = new int[dateCount];
        for (MarketPanel.Stock s : stocks) {
            if (!s.qualityOk) continue;
            for (int i = 0; i < s.rows(); i++) counts[s.day[i]]++;
        }
        int[][] rows = new int[dateCount][];
        for (int d = 0; d < dateCount; d++) rows[d] = new int[counts[d]];
        int[] cursor = new int[dateCount];
        for (MarketPanel.Stock s : stocks) {
            if (!s.qualityOk) continue;
            int si = s.idx;
            for (int i = 0; i < s.rows(); i++) {
                int d = s.day[i];
                rows[d][cursor[d]++] = MarketPanel.pack(si, i);
            }
        }
        return rows;
    }

    /** 股票池等权指数：每日可交易非 ST 标的的 pct 平均值逐日累乘（基期 1.0）。 */
    private double[] buildBenchmark(MarketPanel.Stock[] stocks, int[][] dayRows, int dateCount) {
        double[] nav = new double[dateCount];
        double v = 1d;
        boolean started = false;
        for (int d = 0; d < dateCount; d++) {
            double sum = 0;
            int cnt = 0;
            for (int packed : dayRows[d]) {
                MarketPanel.Stock s = stocks[MarketPanel.stockOf(packed)];
                int r = MarketPanel.rowOf(packed);
                if (!s.tradable[r] || s.st[r] || s.preclose[r] <= 0) continue;
                sum += s.pct[r] / 100d;
                cnt++;
            }
            if (cnt > 0) {
                if (!started) {
                    v = 1d;
                    started = true;
                } else {
                    v *= (1 + sum / cnt);
                }
            }
            nav[d] = v;
        }
        return nav;
    }

    private void saveSnapshot(MarketPanel p) {
        try {
            jdbc.update("""
                    INSERT INTO quant_data_snapshot(snapshot_key, row_count, stock_count, date_from, date_to,
                                                    quality_json, load_cost_ms, loaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE row_count = VALUES(row_count), stock_count = VALUES(stock_count),
                                            date_from = VALUES(date_from), date_to = VALUES(date_to),
                                            quality_json = VALUES(quality_json),
                                            load_cost_ms = VALUES(load_cost_ms), loaded_at = NOW()
                    """, p.snapshotKey(), p.rowCount(), p.stockCount(), Date.valueOf(p.dateAt(0)),
                    Date.valueOf(p.dateAt(p.dateCount() - 1)), toJson(p.quality().toMap()), p.loadCostMs());
        } catch (Exception e) {
            log.warn("[QUANT] 快照记录写入失败：{}", e.getMessage());
        }
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 单只股票的临时缓冲（读表阶段使用，读完整只股票后一次性转成定长数组）。 */
    private static final class Builder {
        final String code;
        final String market;
        int size;
        int[] day = new int[512];
        float[] open = new float[512], high = new float[512], low = new float[512], close = new float[512];
        float[] preclose = new float[512], pct = new float[512], volume = new float[512], amount = new float[512];
        float[] pe = new float[512], pb = new float[512];
        boolean[] tradable = new boolean[512], st = new boolean[512];

        Builder(String code, String market) {
            this.code = code;
            this.market = market;
        }

        void add(ResultSet rs, int dateIdx) throws java.sql.SQLException {
            if (size == day.length) grow();
            int i = size;
            day[i] = dateIdx;
            open[i] = f(rs, 4);
            high[i] = f(rs, 5);
            low[i] = f(rs, 6);
            close[i] = f(rs, 7);
            preclose[i] = f(rs, 8);
            volume[i] = f(rs, 9);
            amount[i] = f(rs, 10);
            pct[i] = f(rs, 11);
            int ts = rs.getInt(12);
            boolean wasNull = rs.wasNull();
            int stFlag = rs.getInt(13);
            pe[i] = f(rs, 14);
            pb[i] = f(rs, 15);
            tradable[i] = !wasNull && ts == 1 && volume[i] > 0 && close[i] > 0 && open[i] > 0;
            st[i] = stFlag == 1;
            size++;
        }

        void grow() {
            int cap = day.length * 2;
            day = java.util.Arrays.copyOf(day, cap);
            open = java.util.Arrays.copyOf(open, cap);
            high = java.util.Arrays.copyOf(high, cap);
            low = java.util.Arrays.copyOf(low, cap);
            close = java.util.Arrays.copyOf(close, cap);
            preclose = java.util.Arrays.copyOf(preclose, cap);
            pct = java.util.Arrays.copyOf(pct, cap);
            volume = java.util.Arrays.copyOf(volume, cap);
            amount = java.util.Arrays.copyOf(amount, cap);
            pe = java.util.Arrays.copyOf(pe, cap);
            pb = java.util.Arrays.copyOf(pb, cap);
            tradable = java.util.Arrays.copyOf(tradable, cap);
            st = java.util.Arrays.copyOf(st, cap);
        }

        static float f(ResultSet rs, int idx) throws java.sql.SQLException {
            double v = rs.getDouble(idx);
            return rs.wasNull() ? Float.NaN : (float) v;
        }
    }
}
