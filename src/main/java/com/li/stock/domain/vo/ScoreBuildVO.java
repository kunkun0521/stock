package com.li.stock.domain.vo;

import java.time.LocalDate;

/** 一次实时评分任务的执行摘要。 */
public record ScoreBuildVO(
        LocalDate tradeDate,
        Integer adjustFlag,
        int sourceStocks,
        int scoredStocks,
        int savedStocks,
        long elapsedMs
) {}
