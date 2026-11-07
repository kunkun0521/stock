package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * /api/t/next-plan 请求：给定当前真实持仓，输出下一交易日挂单。
 *
 * @param sellableQty 当前可卖数量（T+1 口径）
 * @param avgCost     券商显示的持仓成本价
 * @param asOfDate    计划基准日，默认最新交易日
 */
public record PlanRequest(
        String code,
        String market,
        Integer qty,
        Integer sellableQty,
        Double avgCost,
        BigDecimal cash,
        BigDecimal totalCapital,
        LocalDate asOfDate,
        Map<String, Object> params) {
}
