package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一行日行情。
 */
public record StockDailyVO(
        String code,
        String market,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low,
        BigDecimal preclose,
        Long volume,
        BigDecimal amount,
        BigDecimal amplitude,
        BigDecimal pctChg,
        BigDecimal chgAmt,
        BigDecimal turnover,
        Integer adjustFlag,
        Integer tradeStatus,
        Integer isSt,
        BigDecimal peTtm,
        BigDecimal pbMrq,
        BigDecimal psTtm,
        BigDecimal pcfNcfTtm,
        String dataSource) {
}
