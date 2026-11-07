package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 规格十一节公共请求字段 + /api/t/simulate。
 *
 * @param initQty      初始买入股数，与 initAmount 二选一
 * @param initAmount   初始买入金额，与 initQty 二选一
 * @param params       只传需要覆盖的参数，其余使用 /api/t/params/default 的默认值
 */
public record SimulateRequest(
        String code,
        String market,
        LocalDate buyDate,
        Double buyPrice,
        Integer initQty,
        Double initAmount,
        BigDecimal totalCapital,
        BigDecimal availableCash,
        LocalDate endDate,
        Map<String, Object> params) {
}
