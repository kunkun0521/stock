package com.li.stock.quant.strategy;

import com.li.stock.quant.data.MarketPanel;

import java.util.Locale;
import java.util.Set;

/**
 * 股票池与可交易性过滤（规格 3.2 / 6）。
 *
 * <p>股票级：数据质检合格、板块允许、上市满 60 个交易日；
 * 行级：当日可交易（tradestatus=1 且 volume&gt;0）、非 ST（可配置）、
 * 20 日均成交额达标（可配置）。</p>
 */
public final class Universe {

    private static final int NEW_LISTING_BARS = 60;

    private final MarketPanel panel;
    private final boolean allowSt;
    private final Set<String> boards;
    private final boolean allowNewListing;
    private final double minAmountWan;
    private final boolean[] stockOk;
    private volatile float[][] amountMa20;

    public Universe(MarketPanel panel, boolean allowSt, Set<String> boards,
                    boolean allowNewListing, double minAmountWan) {
        this.panel = panel;
        this.allowSt = allowSt;
        this.boards = boards;
        this.allowNewListing = allowNewListing;
        this.minAmountWan = minAmountWan;
        this.stockOk = new boolean[panel.stockCount()];
        for (MarketPanel.Stock s : panel.stocks()) {
            stockOk[s.idx] = s.qualityOk && boards.contains(s.board.toUpperCase(Locale.ROOT));
        }
    }

    public MarketPanel panel() {
        return panel;
    }

    public boolean stockOk(int stockIdx) {
        return stockIdx >= 0 && stockIdx < stockOk.length && stockOk[stockIdx];
    }

    /** 行级可交易性：股票合格 + 非停牌 + 非 ST（按配置）+ 流动性达标。 */
    public boolean rowOk(int stockIdx, int row) {
        if (!stockOk(stockIdx)) return false;
        MarketPanel.Stock s = panel.stocks()[stockIdx];
        if (row < 0 || row >= s.rows()) return false;
        if (!s.tradable[row]) return false;
        if (!allowSt && s.st[row]) return false;
        if (!allowNewListing && s.day[0] > 0 && row < NEW_LISTING_BARS) return false;
        if (minAmountWan > 0) {
            float[][] ma = amountMa20();
            float[] rowVals = ma == null ? null : ma[stockIdx];
            if (rowVals == null || Float.isNaN(rowVals[row])) return false;
            return rowVals[row] / 10000d >= minAmountWan;
        }
        return true;
    }

    /** 当日是否可成交（停牌返回 false），用于撮合。 */
    public boolean tradable(int stockIdx, int row) {
        if (stockIdx < 0 || stockIdx >= stockOk.length) return false;
        MarketPanel.Stock s = panel.stocks()[stockIdx];
        return row >= 0 && row < s.rows() && s.tradable[row];
    }

    /** 20 日均成交额（元），惰性计算一次。 */
    private float[][] amountMa20() {
        float[][] local = amountMa20;
        if (local != null) return local;
        synchronized (this) {
            local = amountMa20;
            if (local != null) return local;
            MarketPanel.Stock[] stocks = panel.stocks();
            float[][] out = new float[stocks.length][];
            for (MarketPanel.Stock s : stocks) {
                if (!s.qualityOk) continue;
                double[] ma = com.li.stock.quant.indicator.Indicators.sma(s.amount, 20);
                float[] f = new float[ma.length];
                for (int i = 0; i < ma.length; i++) f[i] = (float) ma[i];
                out[s.idx] = f;
            }
            amountMa20 = out;
            return out;
        }
    }
}
