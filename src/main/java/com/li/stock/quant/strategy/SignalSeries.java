package com.li.stock.quant.strategy;

/**
 * 单只股票的逐日信号序列，数组长度 = 该股票的行数。
 *
 * <p>约定：{@code entry[i]} 表示第 i 日收盘后产生买入信号（次日开盘成交）；
 * {@code exit[i]} 表示第 i 日收盘后产生卖出信号；{@code stopPrice[i]} 与
 * {@code trailOffset[i]} 供引擎执行价格类风控。策略不应直接读写仓位。</p>
 */
public final class SignalSeries {

    public final int stockIdx;
    public final boolean[] entry;
    public final float[] score;
    public final boolean[] exit;
    public final String[] exitReason;
    /** 建议初始止损价（按信号日收盘估算），无则 NaN。 */
    public final float[] stopPrice;
    /** 固定止损百分比（相对实际买入价），0 表示不设。 */
    public final float[] stopPct;
    /** 移动止盈回撤幅度（价格单位），0 表示不做移动止盈。 */
    public final float[] trailOffset;
    /** 移动止盈回撤百分比（相对持仓期最高收盘价），0 表示不启用。 */
    public final float[] trailPct;
    /** 假突破参考位（如突破前的 N 日高点），引擎在买入后若干日内跌破该位即离场；NaN 表示不启用。 */
    public final float[] breakLevel;
    /** 触发条件明细，仅 explain 模式下填充，否则为 null。 */
    public final String[] detail;

    public SignalSeries(int stockIdx, int rows, boolean explain) {
        this.stockIdx = stockIdx;
        this.entry = new boolean[rows];
        this.score = new float[rows];
        this.exit = new boolean[rows];
        this.exitReason = new String[rows];
        this.stopPrice = new float[rows];
        this.stopPct = new float[rows];
        this.trailOffset = new float[rows];
        this.trailPct = new float[rows];
        this.breakLevel = new float[rows];
        java.util.Arrays.fill(breakLevel, Float.NaN);
        this.detail = explain ? new String[rows] : null;
        java.util.Arrays.fill(stopPrice, Float.NaN);
    }

    public void markExit(int row, String reason) {
        exit[row] = true;
        if (exitReason[row] == null) exitReason[row] = reason;
    }
}
