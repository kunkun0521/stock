package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格 5.1 的持仓状态。金额字段用 double 参与内存计算，输出时由 web 层转 BigDecimal。
 * <p>派生量：</p>
 * <ul>
 *   <li>摊薄成本 dilutedCost = (cumBuyCost - cumSellNet) / qty，做T赚的钱会让它下降</li>
 *   <li>总盈亏 totalPnL = qty * close + cumSellNet - cumBuyCost，回本即 totalPnL >= 0</li>
 *   <li>净值 equity = cash + qty * close</li>
 * </ul>
 */
public class PositionState {

    public int qty;
    /** 当日开盘时的持仓数（T+1：当日买入的股票不计入可卖）。 */
    public int sellableQty;
    public double cash;
    /** 累计买入总支出，含费用。 */
    public double cumBuyCost;
    /** 累计卖出净收入，扣费后。 */
    public double cumSellNet;
    public int addCount;
    public LocalDate lastAddDate;
    /** 上次补仓的交易日索引，-1 表示尚未补仓（间隔按交易日而非自然日计算）。 */
    public int lastAddDayIndex = -1;
    public Pending pending;
    /** cumBuyCost - cumSellNet 的历史最大值，用于收益率与止损口径。 */
    public double maxInvested;
    /** 已持有交易日数。 */
    public int holdDays;
    /** 是否已清仓离场。 */
    public boolean closed;
    /** 累计手续费。 */
    public double feeTotal;
    /** 完整往返做T次数。 */
    public int tRoundTrips;
    /** 遗留头寸次数。 */
    public int tPendingCount;
    /** 做T已实现净收益（元）。 */
    public double tNetProfit;
    /** 做T盈利的往返次数，用于胜率。 */
    public int tWinCount;
    /** 补仓累计金额（元）。 */
    public double addAmount;
    /** 最近一次买入日，用于间隔判断。 */
    public LocalDate lastBuyDate;

    public double dilutedCost() {
        return qty > 0 ? (cumBuyCost - cumSellNet) / qty : 0d;
    }

    public double totalPnL(double close) {
        return qty * close + cumSellNet - cumBuyCost;
    }

    public double equity(double close) {
        return cash + qty * close;
    }

    public double netInvested() {
        return cumBuyCost - cumSellNet;
    }

    /** 每次资金变动后刷新最大占用资金。 */
    public void refreshMaxInvested() {
        maxInvested = Math.max(maxInvested, netInvested());
    }

    public PositionState copy() {
        PositionState c = new PositionState();
        c.qty = qty;
        c.sellableQty = sellableQty;
        c.cash = cash;
        c.cumBuyCost = cumBuyCost;
        c.cumSellNet = cumSellNet;
        c.addCount = addCount;
        c.lastAddDate = lastAddDate;
        c.lastAddDayIndex = lastAddDayIndex;
        c.pending = pending;
        c.maxInvested = maxInvested;
        c.holdDays = holdDays;
        c.closed = closed;
        c.feeTotal = feeTotal;
        c.tRoundTrips = tRoundTrips;
        c.tPendingCount = tPendingCount;
        c.tNetProfit = tNetProfit;
        c.tWinCount = tWinCount;
        c.addAmount = addAmount;
        c.lastBuyDate = lastBuyDate;
        return c;
    }
}
