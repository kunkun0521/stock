package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格 5.3 B 的遗留头寸：做T只成交一腿时登记，pending 存在期间不再开新的做T。
 *
 * @param kind     BUYBACK（只成交卖腿，欠一次回补）或 SELL（只成交买腿，多出待卖头寸）
 * @param refPx    遗留头寸的参考价（成交腿的限价），用于计算回补 / 减仓限价
 * @param qty      遗留股数（100 的整数倍）
 * @param openDate 登记日
 * @param heldDays 已持有交易日数，超过 maxCarryDays 按 carryPolicy 处理
 */
public record Pending(String kind, double refPx, int qty, LocalDate openDate, int heldDays) {

    public static final String BUYBACK = "BUYBACK";
    public static final String SELL = "SELL";

    public Pending withHeldDays(int days) {
        return new Pending(kind, refPx, qty, openDate, days);
    }
}
