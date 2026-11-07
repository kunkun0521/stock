package com.li.stock.domain.model;

/**
 * 做T模拟中的操作类型。buy=true 表示资金流出（买入方向）。
 */
public enum TTradeAction {
    BUY("建仓", true),
    ADD("补仓", true),
    SELL_T("正T高抛", false),
    BUY_T("正T买回", true),
    REVERSE_T_REDUCE("倒T减仓", false),
    SELL_REDUCE("减仓", false),
    EXIT("离场", false),
    HOLD("持有", true);

    private final String label;
    private final boolean buy;

    TTradeAction(String label, boolean buy) {
        this.label = label;
        this.buy = buy;
    }

    public String label() {
        return label;
    }

    public boolean buy() {
        return buy;
    }

    public String code() {
        return name().toLowerCase();
    }
}
