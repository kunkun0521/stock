package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/t/simulate 响应。金额一律 BigDecimal 保留两位小数，指标比率用 double。
 *
 * @param benchmarks 三条净值曲线（含做T策略 / 只补仓不做T / 买入持有）与差值
 * @param kline      回放区间的日K线 + MA + 摊薄成本 + 事件标记，供前端主图
 */
public record SimulateResultVO(
        Long taskId,
        String code,
        String market,
        String name,
        LocalDate buyDate,
        LocalDate endDate,
        int adjustFlag,
        BigDecimal buyPrice,
        int initQty,
        BigDecimal totalCapital,
        BigDecimal availableCash,
        Map<String, Object> summary,
        List<Map<String, Object>> daily,
        List<Map<String, Object>> trades,
        Map<String, Object> benchmarks,
        List<Map<String, Object>> kline,
        List<String> warnings) {
}
