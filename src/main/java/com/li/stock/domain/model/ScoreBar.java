package com.li.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 评分计算所需的最小日线字段。 */
public record ScoreBar(
        String code,
        String market,
        LocalDate tradeDate,
        BigDecimal close,
        BigDecimal amount,
        BigDecimal turnover,
        Integer tradeStatus,
        Integer isSt,
        BigDecimal peTtm,
        BigDecimal pbMrq,
        BigDecimal psTtm,
        BigDecimal pcfNcfTtm
) {}
