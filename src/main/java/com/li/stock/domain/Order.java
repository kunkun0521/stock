package com.li.stock.domain;

/**
 * 一条挂单。StrategyEngine 只产出挂单（纯函数，不含当日K线），
 * ExecutionSimulator 才根据当日 K 线判断是否成交。
 *
 * @param kind         挂单类型：ADD / T_SELL / T_BUY / BUYBACK / PENDING_SELL / EXIT / STOP / FORCE_BUYBACK / CARRY_ABSORB
 * @param side         BUY 或 SELL
 * @param limitPrice   限价（市价类成交时为 0，由撮合器按开盘价 + 滑点定价）
 * @param qty          股数，已向下取整到 100 的整数倍
 * @param reservedCash 该单预留资金（买入腿），用于按优先级分配现金
 * @param marketOnOpen 是否按开盘价市价成交（止损 / 时间止损 / 回本跳空）
 * @param group        做T双向挂单共享同一组标识，撮合器据此做「两腿联合判定」
 * @param reason       触发依据或不触发原因，次日计划直接展示给前端
 */
public record Order(
        String kind,
        String side,
        double limitPrice,
        int qty,
        double reservedCash,
        boolean marketOnOpen,
        String group,
        String reason) {

    public static final String ADD = "ADD";
    public static final String T_SELL = "T_SELL";
    public static final String T_BUY = "T_BUY";
    public static final String BUYBACK = "BUYBACK";
    public static final String PENDING_SELL = "PENDING_SELL";
    public static final String EXIT = "EXIT";
    public static final String STOP = "STOP";
    public static final String FORCE_BUYBACK = "FORCE_BUYBACK";
    public static final String CARRY_ABSORB = "CARRY_ABSORB";

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";

    /** 同日冲突优先级：数值越小越先占用资金与持仓。 */
    public int priority() {
        return switch (kind) {
            case STOP -> 0;
            case EXIT -> 1;
            case ADD -> 2;
            case BUYBACK, PENDING_SELL, FORCE_BUYBACK, CARRY_ABSORB -> 3;
            case T_SELL, T_BUY -> 4;
            default -> 9;
        };
    }

    public static Order of(String kind, String side, double limitPrice, int qty,
                           double reservedCash, boolean marketOnOpen, String group, String reason) {
        return new Order(kind, side, limitPrice, qty, reservedCash, marketOnOpen, group, reason);
    }
}
