package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 市场宽度时序点：近 N 个交易日的涨跌家数与成交额，用于趋势图。
 */
public record BreadthPointVO(
        LocalDate tradeDate,
        int stockCount,
        int upCount,
        int downCount,
        BigDecimal totalAmount,
        BigDecimal avgPctChg) {
}
