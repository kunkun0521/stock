package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * K 线单点数据，含常用技术指标。指标值在数据不足时为 null。
 */
public record KlinePointVO(
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
        Integer tradeStatus,
        /* 均线 */
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma60,
        /* MACD */
        BigDecimal dif,
        BigDecimal dea,
        BigDecimal macd,
        /* RSI */
        BigDecimal rsi6,
        BigDecimal rsi12,
        BigDecimal rsi24,
        /* 布林带 */
        BigDecimal bollUpper,
        BigDecimal bollMid,
        BigDecimal bollLower) {
}
