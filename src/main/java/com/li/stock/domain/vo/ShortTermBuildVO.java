package com.li.stock.domain.vo;

import java.time.LocalDate;

/** 短期预测任务执行摘要。 */
public record ShortTermBuildVO(
        LocalDate tradeDate,
        Integer adjustFlag,
        int sourceStocks,
        int forecastStocks,
        int savedRows,
        int[] horizons,
        long elapsedMs
) {}
