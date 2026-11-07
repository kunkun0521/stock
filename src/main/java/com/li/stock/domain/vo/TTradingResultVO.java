package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 做T + 成本摊薄模拟的完整结果。
 * <p>收益率口径：总盈亏 = 期末总资产（现金 + 持仓市值）− 期初总资产（现金 + 底仓市值），
 * 已包含手续费；摊薄成本为剩余持仓的回本价，未持仓时为 null。</p>
 * <p>initialAvgCost 为当前这批持仓的建仓价（中途清仓重建仓后自动切换到新周期）；
 * costReductionPct 是摊薄成本相对账面成本的降幅，等于累计做T价差 ÷ 剩余股数 ÷ 账面成本。</p>
 *
 * @param status   持仓中 / 已离场 / 未建仓
 * @param summary  对本次模拟的一句话结论
 */
public record TTradingResultVO(
        String code,
        String market,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        int tradingDays,
        int adjustFlag,
        BigDecimal totalCapital,
        BigDecimal initialAssets,
        BigDecimal finalCash,
        int finalPosition,
        BigDecimal initialAvgCost,
        BigDecimal avgCost,
        BigDecimal dilutedCost,
        BigDecimal costReductionPct,
        BigDecimal lastClose,
        BigDecimal marketValue,
        BigDecimal totalAssets,
        BigDecimal realizedPnl,
        BigDecimal floatingPnl,
        BigDecimal totalPnl,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal feeTotal,
        int tradeCount,
        int addCount,
        int tBuybackCount,
        int reverseReduceCount,
        String status,
        String summary,
        List<TTradeRecordVO> trades,
        List<TDailyPointVO> equity,
        List<TAdviceVO> advices,
        TStrategyConfigVO config) {
}
