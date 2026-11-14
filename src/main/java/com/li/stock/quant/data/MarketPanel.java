package com.li.stock.quant.data;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全市场行情面板（只读）。
 *
 * <p>一次性把 {@code stock_daily_all} 中 adjustflag=2 的行读入内存，按股票切成紧凑数组，
 * 回测/事件研究/每日选股都复用同一份面板，避免每次请求都扫 155 万行。面板不可变，
 * 由 {@link PanelService} 按快照缓存。</p>
 *
 * <p>索引约定：{@code dayRows[d]} 存当天所有可交易行的打包索引
 * {@code (stockIdx << 10) | rowIdx}，行号 &lt; 1024，因此横截面计算与基准指数
 * 只需遍历当天存在数据的股票，不用扫全表。</p>
 */
public final class MarketPanel {

    /** 打包索引：高 22 位股票下标，低 10 位行号（单只股票最多 1024 个交易日）。 */
    public static final int ROW_BITS = 10;
    public static final int ROW_MASK = (1 << ROW_BITS) - 1;

    private final String snapshotKey;
    private final LocalDate[] dates;
    private final Stock[] stocks;
    private final int[][] dayRows;
    private final long rowCount;
    private final long loadedAt;
    private final long loadCostMs;
    private final double[] benchmarkNav;
    private final Quality quality;

    public MarketPanel(String snapshotKey, LocalDate[] dates, Stock[] stocks, int[][] dayRows,
                       long rowCount, long loadedAt, long loadCostMs, double[] benchmarkNav, Quality quality) {
        this.snapshotKey = snapshotKey;
        this.dates = dates;
        this.stocks = stocks;
        this.dayRows = dayRows;
        this.rowCount = rowCount;
        this.loadedAt = loadedAt;
        this.loadCostMs = loadCostMs;
        this.benchmarkNav = benchmarkNav;
        this.quality = quality;
    }

    public String snapshotKey() {
        return snapshotKey;
    }

    public LocalDate[] dates() {
        return dates;
    }

    public int dateCount() {
        return dates.length;
    }

    public Stock[] stocks() {
        return stocks;
    }

    public int stockCount() {
        return stocks.length;
    }

    public int[][] dayRows() {
        return dayRows;
    }

    public long rowCount() {
        return rowCount;
    }

    public long loadedAt() {
        return loadedAt;
    }

    public long loadCostMs() {
        return loadCostMs;
    }

    public double[] benchmarkNav() {
        return benchmarkNav;
    }

    public Quality quality() {
        return quality;
    }

    /** 日期下标，找不到返回 -1。 */
    public int dateIndex(LocalDate date) {
        if (date == null) return -1;
        return Arrays.binarySearch(dates, date);
    }

    public LocalDate dateAt(int idx) {
        return idx >= 0 && idx < dates.length ? dates[idx] : null;
    }

    public static int stockOf(int packed) {
        return packed >>> ROW_BITS;
    }

    public static int rowOf(int packed) {
        return packed & ROW_MASK;
    }

    public static int pack(int stockIdx, int rowIdx) {
        return (stockIdx << ROW_BITS) | rowIdx;
    }

    /** 单只股票的紧凑日线序列，数组长度 = 该股票实际存在的交易日数。 */
    public static final class Stock {
        public final int idx;
        public final String code;
        public final String market;
        /** MAIN / GEM / STAR / BJ / OTHER */
        public final String board;
        /** 每行对应的全局日期下标，升序。 */
        public final int[] day;
        public final float[] open;
        public final float[] high;
        public final float[] low;
        public final float[] close;
        public final float[] preclose;
        /** 涨跌幅，百分数（-2.54 表示 -2.54%）。 */
        public final float[] pct;
        /** 成交量，股。 */
        public final float[] volume;
        /** 成交额，元。 */
        public final float[] amount;
        public final float[] pe;
        public final float[] pb;
        public final boolean[] tradable;
        public final boolean[] st;
        public final boolean qualityOk;

        public Stock(int idx, String code, String market, int[] day, float[] open, float[] high, float[] low,
                     float[] close, float[] preclose, float[] pct, float[] volume, float[] amount,
                     float[] pe, float[] pb, boolean[] tradable, boolean[] st, boolean qualityOk) {
            this.idx = idx;
            this.code = code;
            this.market = market;
            this.board = boardOf(code);
            this.day = day;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.preclose = preclose;
            this.pct = pct;
            this.volume = volume;
            this.amount = amount;
            this.pe = pe;
            this.pb = pb;
            this.tradable = tradable;
            this.st = st;
            this.qualityOk = qualityOk;
        }

        public int rows() {
            return day.length;
        }

        /** 该股票在指定全局日期下标上的行号，没有数据返回 -1。 */
        public int rowOn(int dateIdx) {
            int p = Arrays.binarySearch(day, dateIdx);
            return p >= 0 ? p : -1;
        }

        /** 涨跌停幅度（百分数）：ST 5%，创业板/科创板 20%，其余 10%。 */
        public double limitPct(int row) {
            if (row >= 0 && row < st.length && st[row]) return 5d;
            return switch (board) {
                case "GEM", "STAR" -> 20d;
                default -> 10d;
            };
        }

        public static String boardOf(String code) {
            if (code == null || code.length() < 2) return "OTHER";
            if (code.startsWith("68")) return "STAR";
            if (code.startsWith("30")) return "GEM";
            if (code.startsWith("60") || code.startsWith("00")) return "MAIN";
            if (code.startsWith("8") || code.startsWith("4")) return "BJ";
            return "OTHER";
        }
    }

    /** 数据质检摘要（只报告，不修改行情表）。 */
    public record Quality(int stockCount, int droppedStocks, long badRows, long suspendedRows,
                          long stRows, double avgMissingPct, double loadCostMs) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stockCount", stockCount);
            m.put("droppedStocks", droppedStocks);
            m.put("badRows", badRows);
            m.put("suspendedRows", suspendedRows);
            m.put("stRows", stRows);
            m.put("avgMissingPct", round(avgMissingPct, 4));
            m.put("loadCostMs", (long) loadCostMs);
            return m;
        }

        private static double round(double v, int scale) {
            double f = Math.pow(10, scale);
            return Math.round(v * f) / f;
        }
    }
}
