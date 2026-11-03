package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 单只股票未来第 N 个交易日的预测、实际结果与误差。 */
public record ShortTermDailyForecastVO(
        String code,
        String name,
        String market,
        String exchange,
        String board,
        String industry,
        LocalDate forecastDate,
        Long runId,
        Integer horizonDays,
        LocalDate actualTargetDate,
        BigDecimal predictedDailyReturn,
        BigDecimal predictedCumulativeReturn,
        BigDecimal target15Probability,
        BigDecimal confidence,
        BigDecimal forecastScore,
        BigDecimal actualDailyReturn,
        BigDecimal actualCumulativeReturn,
        BigDecimal predictionError,
        String evaluationStatus
) {}
