package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * /api/t/scan-entry 请求。公共字段与 {@link SimulateRequest} 一致，
 * 额外字段 holdDays / initPositionRatio 也可放在 params 中覆盖。
 *
 * @param fromDate 扫描区间起点（可空，默认第 21 个交易日）
 */
public record ScanRequest(
        String code,
        String market,
        LocalDate fromDate,
        BigDecimal totalCapital,
        BigDecimal availableCash,
        Integer holdDays,
        Double initPositionRatio,
        Map<String, Object> params) {
}
