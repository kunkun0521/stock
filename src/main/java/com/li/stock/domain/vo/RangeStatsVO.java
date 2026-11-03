package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 区间统计：区间涨跌幅、极值、波动率、平均成交情况。
 */
public record RangeStatsVO(
        String code,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        int tradingDays,
        BigDecimal firstClose,
        BigDecimal lastClose,
        BigDecimal returnPct,
        BigDecimal highestClose,
        LocalDate highestCloseDate,
        BigDecimal lowestClose,
        LocalDate lowestCloseDate,
        BigDecimal maxPctChg,
        LocalDate maxPctChgDate,
        BigDecimal minPctChg,
        LocalDate minPctChgDate,
        BigDecimal totalAmount,
        BigDecimal avgAmount,
        BigDecimal avgTurnover,
        BigDecimal annualizedVolatility) {
}
