package com.li.stock.domain.model;

import com.li.stock.domain.query.TStrategyParams;

import java.math.BigDecimal;

/**
 * 归一化后的策略参数：全部为确定值，策略引擎与模拟器只读这里，不再处理 null。
 * <p>默认值对齐 Python 版思路，并补上 A 股实务参数（费用、T+1 等待天数、倒T反弹阈值、
 * 摊薄成本达标线）。{@code of(...)} 会对外部传入值做范围钳制，避免异常参数导致死循环或超额下单。</p>
 */
public record TTradingConfig(
        double addDropPct,
        double addPositionRatio,
        double maxPositionRatio,
        double tProfitPct,
        double tSellRatio,
        double tBuybackDrop,
        int tMaxHoldDays,
        double reverseReboundPct,
        double reduceProfitPct,
        double exitProfitPct,
        double stopLossPct,
        double costReductionTargetPct,
        boolean useMaFilter,
        double minTurnover,
        double maxAmplitude,
        boolean includeFee,
        double commissionRate,
        double minCommission,
        double stampDutyRate,
        double transferFeeRate,
        double initialPositionRatio) {

    public static TTradingConfig defaults() {
        return of(null);
    }

    public static TTradingConfig of(TStrategyParams p) {
        TStrategyParams v = p == null ? empty() : p;
        return new TTradingConfig(
                pick(v.addDropPct(), 0.05, 0.005, 0.50),
                pick(v.addPositionRatio(), 0.30, 0.05, 1.00),
                pick(v.maxPositionRatio(), 0.80, 0.10, 1.00),
                pick(v.tProfitPct(), 0.04, 0.005, 0.50),
                pick(v.tSellRatio(), 0.30, 0.05, 0.90),
                pick(v.tBuybackDrop(), 0.02, 0.002, 0.30),
                pickInt(v.tMaxHoldDays(), 10, 1, 60),
                pick(v.reverseReboundPct(), 0.03, 0.002, 0.50),
                pick(v.reduceProfitPct(), 0.08, 0.005, 1.00),
                pick(v.exitProfitPct(), 0.15, 0.01, 2.00),
                pick(v.stopLossPct(), -0.12, -0.80, -0.01),
                pick(v.costReductionTargetPct(), 0.05, 0.0, 0.90),
                pickBool(v.useMaFilter(), true),
                // 大盘股换手率长期在 0.2~0.8 之间，默认 1% 会几乎屏蔽所有补仓/建仓；
                // 0.3 只过滤极度不活跃的标的，填 0 表示完全关闭该过滤
                pick(v.minTurnover(), 0.3, 0.0, 50.0),
                pick(v.maxAmplitude(), 9.5, 1.0, 30.0),
                pickBool(v.includeFee(), true),
                pick(v.commissionRate(), 0.00025, 0.0, 0.003),
                pick(v.minCommission(), 5.0, 0.0, 100.0),
                pick(v.stampDutyRate(), 0.0005, 0.0, 0.003),
                pick(v.transferFeeRate(), 0.00001, 0.0, 0.001),
                // 首日只用 70% 现金建仓，留出做T与补仓的弹药；用满 95% 会导致后续无钱可用
                pick(v.initialPositionRatio(), 0.70, 0.10, 1.00));
    }

    /** 单笔交易费用：佣金（有最低 5 元）+ 卖出印花税 + 双向过户费。 */
    public double fee(double turnover, boolean sell) {
        if (!includeFee || turnover <= 0) {
            return 0d;
        }
        double commission = Math.max(turnover * commissionRate, minCommission);
        double stamp = sell ? turnover * stampDutyRate : 0d;
        return commission + stamp + turnover * transferFeeRate;
    }

    /** 买入时的现金流出上限估算（含费用），用于计算可买整手数。 */
    public double buyCost(double price, int shares) {
        double turnover = price * shares;
        return turnover + fee(turnover, false);
    }

    private static TStrategyParams empty() {
        return new TStrategyParams(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static double pick(BigDecimal value, double fallback, double min, double max) {
        if (value == null) {
            return fallback;
        }
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return fallback;
        }
        return Math.min(Math.max(d, min), max);
    }

    private static int pickInt(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }

    private static boolean pickBool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
