package com.li.stock.domain;

import java.time.LocalDate;

/**
 * 规格第四节的 IndicatorSet：每个交易日一份，所有字段只用该日及以前的数据计算。
 * <p>策略在 t 日只能读取 t-1 及以前的指标，Backtester 负责把索引对齐。</p>
 *
 * @param atrPct     ATR14 / close
 * @param ma20Slope  MA20 相对 5 日前的变化率
 * @param percentB   布林带 %B（20 日，2 倍标准差）
 * @param volRatio   当日成交量 / 前 5 日均量
 * @param devMa20    close / MA20 - 1
 * @param avgAmp20   近 20 日 amplitude 均值（展示用）
 */
public record Indicator(
        LocalDate date,
        double close,
        double atrPct,
        double ma5,
        double ma10,
        double ma20,
        double ma20Slope,
        double rsi14,
        double percentB,
        double volRatio,
        double ret5,
        double ret20,
        double devMa20,
        double avgAmp20) {
}
