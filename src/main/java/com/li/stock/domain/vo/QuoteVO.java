package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 行情快照：日线数据 + 股票名称等展示字段，榜单与详情页头部共用。
 */
public record QuoteVO(
        String code,
        String name,
        String board,
        String industry,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal preclose,
        BigDecimal pctChg,
        BigDecimal chgAmt,
        Long volume,
        BigDecimal amount,
        BigDecimal turnover,
        BigDecimal amplitude,
        BigDecimal peTtm,
        BigDecimal pbMrq,
        Integer isSt,
        Integer tradeStatus) {
}
