package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条模拟成交记录。shares 正数买入、负数卖出。
 *
 * @param dilutedCostAfter 成交后剩余持仓的回本价
 */
public record TTradeRecordVO(
        LocalDate tradeDate,
        String action,
        String actionLabel,
        BigDecimal price,
        int shares,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal cashAfter,
        int positionAfter,
        BigDecimal avgCostAfter,
        BigDecimal dilutedCostAfter,
        BigDecimal realizedPnlAfter,
        String reason) {
}
