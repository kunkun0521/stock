package com.li.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一个股票在一个持有期限上的短期预测结果。收益率和概率均使用小数表示。 */
public record ForecastResult(
        String code,
        String market,
        LocalDate tradeDate,
        int adjustFlag,
        int horizonDays,
        BigDecimal expectedReturn,
        BigDecimal target15Probability,
        BigDecimal confidence,
        BigDecimal forecastScore,
        int analogCount
) {}
