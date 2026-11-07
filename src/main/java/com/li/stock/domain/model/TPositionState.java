package com.li.stock.domain.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 持仓状态机：集中管理现金、持仓、账面成本、摊薄成本与做T待回补状态。
 * <p>所有买卖只通过 {@link #applyBuy}/{@link #applySell} 变更，保证日志、费用、
 * 净投入三者始终同步。金额用 double 运算，对外统一由 Numbers 转 BigDecimal。</p>
 * <p>两套成本口径：</p>
 * <ul>
 *   <li><b>账面成本 avgCost</b>：移动加权平均，买入摊入费用，卖出不改变，是会计意义上的成本价；</li>
 *   <li><b>摊薄成本 dilutedCost</b> = 累计净投入 / 剩余股数，即「剩余持仓的回本价」。
 *       高抛低吸、补仓反弹减仓的价差都会把它压低，是回本离场判断的依据。</li>
 * </ul>
 */
public final class TPositionState {

    private final double totalCapital;
    private final double initialAssets;
    private final double initialAvgCost;

    private double availableCash;
    private int position;
    private double avgCost;
    private double netInvested;
    private double realizedPnl;
    private double feeTotal;
    private int boughtToday;

    /** 正T高抛后待买回的股数与卖出价，等待回落买回形成闭环。 */
    private int pendingTSellShares;
    private double pendingTSellPrice;
    private int pendingTSellDays;
    /** 补仓后待反弹减掉的股数与补仓价，等待反弹减仓形成倒T闭环。 */
    private int pendingAddShares;
    private double pendingAddPrice;

    private int tradeCount;
    private int tBuybackCount;
    private int reverseReduceCount;
    private int addCount;
    private LocalDate lastExitDate;

    private final List<TradeLog> trades = new ArrayList<>();
    private final List<EquityPoint> equity = new ArrayList<>();

    public TPositionState(double totalCapital, double availableCash,
                          int initialShares, double initialAvgCost) {
        this.totalCapital = totalCapital;
        this.initialAssets = availableCash + initialShares * initialAvgCost;
        this.initialAvgCost = initialAvgCost;
        this.availableCash = availableCash;
        this.position = initialShares;
        this.avgCost = initialAvgCost;
        this.netInvested = initialShares * initialAvgCost;
    }

    /** 每个交易日开始时重置 T+1 计数，并累计待回补天数。 */
    public void newDay() {
        boughtToday = 0;
        if (pendingTSellShares > 0) {
            pendingTSellDays++;
        }
    }

    public void applyBuy(LocalDate date, TTradeAction action, double price, int shares,
                         String reason, TTradingConfig cfg) {
        double turnover = price * shares;
        double fee = cfg.fee(turnover, false);
        double cashOut = turnover + fee;
        if (cashOut > availableCash + 1e-6 || shares <= 0) {
            throw new IllegalArgumentException("买入资金不足或股数非法");
        }
        int newPosition = position + shares;
        avgCost = (avgCost * position + turnover + fee) / newPosition;
        position = newPosition;
        availableCash -= cashOut;
        netInvested += cashOut;
        feeTotal += fee;
        boughtToday += shares;
        tradeCount++;
        if (action == TTradeAction.ADD) {
            addCount++;
            pendingAddShares += shares;
            pendingAddPrice = price;
        } else if (action == TTradeAction.BUY_T) {
            tBuybackCount++;
            pendingTSellShares = Math.max(0, pendingTSellShares - shares);
            if (pendingTSellShares == 0) {
                pendingTSellPrice = 0;
                pendingTSellDays = 0;
            }
        }
        appendLog(date, action, price, shares, turnover, fee, reason);
    }

    public void applySell(LocalDate date, TTradeAction action, double price, int shares,
                          String reason, TTradingConfig cfg) {
        int sellShares = Math.abs(shares);
        if (sellShares <= 0 || sellShares > sellable()) {
            throw new IllegalArgumentException("可卖数量不足（T+1 约束）");
        }
        double turnover = price * sellShares;
        double fee = cfg.fee(turnover, true);
        double cashIn = turnover - fee;
        realizedPnl += (price - avgCost) * sellShares - fee;
        position -= sellShares;
        availableCash += cashIn;
        netInvested -= cashIn;
        feeTotal += fee;
        tradeCount++;
        if (action == TTradeAction.SELL_T) {
            pendingTSellShares += sellShares;
            pendingTSellPrice = price;
            pendingTSellDays = 0;
        } else if (action == TTradeAction.REVERSE_T_REDUCE) {
            reverseReduceCount++;
            pendingAddShares = Math.max(0, pendingAddShares - sellShares);
            if (pendingAddShares == 0) {
                pendingAddPrice = 0;
            }
        } else if (action == TTradeAction.EXIT) {
            lastExitDate = date;
            pendingTSellShares = 0;
            pendingTSellPrice = 0;
            pendingTSellDays = 0;
            pendingAddShares = 0;
            pendingAddPrice = 0;
        }
        if (position == 0) {
            avgCost = 0;
            netInvested = 0;
        }
        appendLog(date, action, price, -sellShares, turnover, fee, reason);
    }

    /** A 股 T+1：当日买入的股份不可卖出。 */
    public int sellable() {
        return Math.max(0, position - boughtToday);
    }

    /** 剩余持仓的回本价；netInvested <= 0 表示本金已通过做T收回。 */
    public double dilutedCost() {
        return position > 0 ? netInvested / position : 0d;
    }

    public double marketValue(double price) {
        return position * price;
    }

    public double totalAssets(double price) {
        return availableCash + marketValue(price);
    }

    public double floatingPnl(double price) {
        return marketValue(price) - position * avgCost;
    }

    public double totalPnl(double price) {
        return totalAssets(price) - initialAssets;
    }

    public double totalReturnPct(double price) {
        return initialAssets > 0 ? totalPnl(price) / initialAssets : 0d;
    }

    public double floatingPnlPct(double price) {
        return avgCost > 0 ? (price - avgCost) / avgCost : 0d;
    }

    /** 单票当前市值是否已达仓位上限。 */
    public boolean positionFull(double price, TTradingConfig cfg) {
        return marketValue(price) >= totalCapital * cfg.maxPositionRatio() - 1e-6;
    }

    private void appendLog(LocalDate date, TTradeAction action, double price, int signedShares,
                           double turnover, double fee, String reason) {
        trades.add(new TradeLog(date, action.code(), action.label(), price, signedShares,
                turnover, fee, availableCash, position, avgCost, dilutedCost(),
                realizedPnl, reason));
    }

    public void addEquity(EquityPoint point) {
        equity.add(point);
    }

    public double totalCapital() {
        return totalCapital;
    }

    public double initialAssets() {
        return initialAssets;
    }

    public double initialAvgCost() {
        return initialAvgCost;
    }

    public double availableCash() {
        return availableCash;
    }

    public int position() {
        return position;
    }

    public double avgCost() {
        return avgCost;
    }

    public double netInvested() {
        return netInvested;
    }

    public double realizedPnl() {
        return realizedPnl;
    }

    public double feeTotal() {
        return feeTotal;
    }

    public int boughtToday() {
        return boughtToday;
    }

    public int pendingTSellShares() {
        return pendingTSellShares;
    }

    public double pendingTSellPrice() {
        return pendingTSellPrice;
    }

    public int pendingTSellDays() {
        return pendingTSellDays;
    }

    public int pendingAddShares() {
        return pendingAddShares;
    }

    public double pendingAddPrice() {
        return pendingAddPrice;
    }

    public int tradeCount() {
        return tradeCount;
    }

    public int tBuybackCount() {
        return tBuybackCount;
    }

    public int reverseReduceCount() {
        return reverseReduceCount;
    }

    public int addCount() {
        return addCount;
    }

    public LocalDate lastExitDate() {
        return lastExitDate;
    }

    public List<TradeLog> trades() {
        return trades;
    }

    public List<EquityPoint> equity() {
        return equity;
    }

    /** 一条成交记录（内部用，出接口时转为 TTradeRecordVO）。 */
    public record TradeLog(LocalDate tradeDate, String action, String actionLabel,
                           double price, int shares, double amount, double fee,
                           double cashAfter, int positionAfter, double avgCostAfter,
                           double dilutedCostAfter, double realizedPnlAfter, String reason) {
    }

    /** 每日资产快照，用于绘制资产曲线与计算最大回撤。 */
    public record EquityPoint(LocalDate tradeDate, double close, double ma20,
                              double avgCost, double dilutedCost, double cash,
                              int position, double totalAssets, double drawdownPct,
                              String actionLabel) {
    }
}
