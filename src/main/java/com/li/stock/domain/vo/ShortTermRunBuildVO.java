package com.li.stock.domain.vo;

import java.time.LocalDate;

/** 新建一次逐日预测批次后的执行摘要。 */
public record ShortTermRunBuildVO(
        Long runId,
        LocalDate forecastDate,
        Integer adjustFlag,
        int sourceStocks,
        int forecastStocks,
        int savedRows,
        int horizonDays,
        long elapsedMs
) {}
