package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * 本次模拟实际生效的策略参数，供页面核对（比例已转成百分数文本所需的原始小数）。
 */
public record TStrategyConfigVO(
        String priceMode,
        BigDecimal addDropPct,
        BigDecimal addPositionRatio,
        BigDecimal maxPositionRatio,
        BigDecimal tProfitPct,
        BigDecimal tSellRatio,
        BigDecimal tBuybackDrop,
        int tMaxHoldDays,
        BigDecimal reverseReboundPct,
        BigDecimal reduceProfitPct,
        BigDecimal exitProfitPct,
        BigDecimal stopLossPct,
        BigDecimal costReductionTargetPct,
        boolean useMaFilter,
        BigDecimal minTurnover,
        BigDecimal maxAmplitude,
        boolean includeFee,
        BigDecimal commissionRate,
        BigDecimal stampDutyRate,
        BigDecimal initialPositionRatio) {
}
