package com.li.stock.domain.vo;

import java.math.BigDecimal;

/**
 * 板块 / 行业维度表现。
 */
public record SectorPerfVO(
        String name,
        int stockCount,
        int upCount,
        int downCount,
        BigDecimal upRatio,
        BigDecimal avgPctChg,
        BigDecimal totalAmount,
        BigDecimal avgTurnover) {
}
