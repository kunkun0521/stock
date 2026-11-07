package com.li.stock.domain.vo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/t/next-plan 响应。
 *
 * @param orders       挂单卡片：补仓 / 做T / 回本卖出 / 止损
 * @param explanations 各挂单为什么触发或为什么不触发
 * @param indicators   基准日的指标快照
 */
public record PlanResultVO(
        Long taskId,
        String code,
        String market,
        String name,
        LocalDate asOfDate,
        List<Map<String, Object>> orders,
        List<String> explanations,
        Map<String, Object> indicators,
        Map<String, Object> levels,
        List<String> warnings) {
}
