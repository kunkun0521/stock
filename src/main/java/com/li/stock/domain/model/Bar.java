package com.li.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * K 线计算用的内部结构，日/周/月统一使用同一形态。
 */
public record Bar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal preclose,
        Long volume,
        BigDecimal amount,
        BigDecimal pctChg,
        BigDecimal chgAmt,
        BigDecimal turnover,
        BigDecimal amplitude,
        Integer isSt,
        Integer tradeStatus) {
}
