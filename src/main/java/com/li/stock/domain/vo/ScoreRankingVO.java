package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 单只股票的多因子综合评分及可解释分项。 */
public record ScoreRankingVO(
        int rank,
        String code,
        String name,
        Integer market,
        String exchange,
        String board,
        String industry,
        LocalDate tradeDate,
        Integer adjustFlag,
        BigDecimal totalScore,
        BigDecimal valueScore,
        BigDecimal momentumScore,
        BigDecimal trendScore,
        BigDecimal riskScore,
        BigDecimal liquidityScore,
        BigDecimal close,
        BigDecimal peTtm,
        BigDecimal pbMrq,
        BigDecimal psTtm,
        BigDecimal pcfNcfTtm,
        BigDecimal ret21,
        BigDecimal ret63,
        BigDecimal ret126,
        BigDecimal ret252,
        BigDecimal vol60,
        BigDecimal drawdown252,
        BigDecimal amountRatio20,
        BigDecimal turnover20
) {}
