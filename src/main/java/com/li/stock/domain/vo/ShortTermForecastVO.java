package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 短期预测排行：同时展示 5/10/15/20 个交易日的预测结果。 */
public record ShortTermForecastVO(
        int rank,
        String code,
        String name,
        Integer market,
        String exchange,
        String board,
        String industry,
        LocalDate tradeDate,
        Integer adjustFlag,
        BigDecimal score20,
        BigDecimal expected5,
        BigDecimal expected10,
        BigDecimal expected15,
        BigDecimal expected20,
        BigDecimal probability5,
        BigDecimal probability10,
        BigDecimal probability15,
        BigDecimal probability20,
        BigDecimal confidence5,
        BigDecimal confidence10,
        BigDecimal confidence15,
        BigDecimal confidence20,
        Integer analogCount20
) {}
