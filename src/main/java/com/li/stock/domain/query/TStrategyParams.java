package com.li.stock.domain.query;

import java.math.BigDecimal;

/**
 * 做T / 成本摊薄策略的可调参数。
 * <p>所有字段均可为 null，页面未传的项由 {@code TTradingConfig.of} 用默认值补齐；
 * 比例类统一传小数（0.05 表示 5%），与前端表单保持一致。</p>
 */
public record TStrategyParams(
        BigDecimal addDropPct,              // 相对成本下跌多少触发补仓
        BigDecimal addPositionRatio,        // 每次补仓动用可用现金的比例
        BigDecimal maxPositionRatio,        // 单票市值占总资金上限
        BigDecimal tProfitPct,              // 相对成本上涨多少触发正T高抛
        BigDecimal tSellRatio,              // 正T卖出持仓比例
        BigDecimal tBuybackDrop,            // 高抛后回落多少买回
        Integer tMaxHoldDays,               // 高抛后最多等待多少交易日强制买回
        BigDecimal reverseReboundPct,       // 补仓后反弹多少减掉补仓部分（倒T）
        BigDecimal reduceProfitPct,         // 浮盈多少触发减仓
        BigDecimal exitProfitPct,           // 浮盈多少触发止盈离场
        BigDecimal stopLossPct,             // 浮亏多少触发止损（负数）
        BigDecimal costReductionTargetPct,  // 摊薄成本需比初始成本低多少才算达标
        Boolean useMaFilter,                // 是否启用均线过滤
        BigDecimal minTurnover,             // 最低换手率（%）
        BigDecimal maxAmplitude,            // 最高振幅（%）
        Boolean includeFee,                 // 是否计入交易费用
        BigDecimal commissionRate,          // 佣金费率
        BigDecimal minCommission,           // 单笔最低佣金（元）
        BigDecimal stampDutyRate,           // 卖出印花税率
        BigDecimal transferFeeRate,         // 过户费率
        BigDecimal initialPositionRatio     // 首日建仓动用可用资金的比例（留出现金做T/补仓）
) {
}
