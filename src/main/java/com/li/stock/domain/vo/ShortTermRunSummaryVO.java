package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 某次预测批次的股票排行摘要，20日为一个月最长持有期。 */
public record ShortTermRunSummaryVO(
        int rank,
        String code,
        String name,
        Integer market,
        String exchange,
        String board,
        String industry,
        LocalDate forecastDate,
        Long runId,
        BigDecimal score20,
        BigDecimal predictedCum5,
        BigDecimal predictedCum10,
        BigDecimal predictedCum15,
        BigDecimal predictedCum20,
        BigDecimal probability20,
        BigDecimal confidence20,
        Integer analogCount20,
        BigDecimal actualCum20,
        BigDecimal error20,
        String status20
) {}
