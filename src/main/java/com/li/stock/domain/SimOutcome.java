package com.li.stock.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Backtester 单次运行的内部产物（金额用 double，对外转换由 web 层负责）。
 *
 * @param equityCurve 与 daily 等长的每日净值曲线
 * @param maxDrawdown 净值曲线最大回撤（正数，如 0.12 表示 12%）
 */
public record SimOutcome(
        List<DailyPoint> daily,
        List<TradeRecord> trades,
        PositionState state,
        List<Double> equityCurve,
        double maxDrawdown,
        LocalDate recoverDate,
        Integer recoverDays,
        double buyPrice,
        int initQty,
        int holdDays,
        double finalPnlPct) {
}
