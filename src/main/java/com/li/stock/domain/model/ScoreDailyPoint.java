package com.li.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** stock_score_daily 中用于连续区间判定的最小字段集合。 */
public record ScoreDailyPoint(
        String code,
        String market,
        LocalDate tradeDate,
        BigDecimal totalScore,
        String name,
        String exchange,
        String board,
        String industry,
        BigDecimal close
) {
}
