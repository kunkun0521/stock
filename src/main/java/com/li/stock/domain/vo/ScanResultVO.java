package com.li.stock.domain.vo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/t/scan-entry 响应。
 *
 * @param rows            每个买入日一行
 * @param featureBuckets  特征分桶统计
 * @param recommendations 综合得分最高的若干买入日
 * @param currentSignal   用最新一根K线的特征回答「现在是不是有利买点」
 * @param stability       前 70% / 后 30% 稳健性
 */
public record ScanResultVO(
        Long taskId,
        String code,
        String market,
        String name,
        int holdDays,
        int windowCount,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        List<Map<String, Object>> rows,
        List<Map<String, Object>> featureBuckets,
        List<Map<String, Object>> recommendations,
        Map<String, Object> currentSignal,
        Map<String, Object> stability,
        List<String> warnings) {
}
