package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格第十节 t_sim_daily 的一行每日快照，也是接口 daily[] 的元素。
 *
 * @param events 当日事件标记，逗号分隔：ADD,T,EXIT,STOP
 */
public record DailyPoint(
        LocalDate tradeDate,
        double close,
        int qty,
        int sellableQty,
        double cash,
        double equity,
        double dilutedCost,
        double totalPnl,
        String events) {
}
