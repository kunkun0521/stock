package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 连续评分区间命中股票的一行明细。
 *
 * @param scoreChange 区间末日综合分减去首日综合分
 * @param series      该连续窗口内逐日综合分，供前端绘制走势
 */
public record ScoreContinuityItemVO(
        String code,
        String name,
        String exchange,
        String board,
        String industry,
        int days,
        String band,
        BigDecimal latestScore,
        BigDecimal firstScore,
        BigDecimal scoreChange,
        BigDecimal avgScore,
        BigDecimal minScore,
        BigDecimal maxScore,
        BigDecimal latestClose,
        LocalDate startDate,
        LocalDate endDate,
        List<ScorePoint> series
) {

    /** 单个交易日的综合评分。 */
    public record ScorePoint(LocalDate tradeDate, BigDecimal score) {
    }
}
