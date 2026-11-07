package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日资产快照。drawdownPct 为相对历史峰值的回撤百分比（负数，如 -8.5 表示 -8.5%）。
 */
public record TDailyPointVO(
        LocalDate tradeDate,
        BigDecimal close,
        BigDecimal ma20,
        BigDecimal avgCost,
        BigDecimal dilutedCost,
        BigDecimal cash,
        int position,
        BigDecimal totalAssets,
        BigDecimal drawdownPct,
        String actionLabel) {
}
