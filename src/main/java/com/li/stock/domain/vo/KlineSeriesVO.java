package com.li.stock.domain.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * K 线序列，直接喂给 ECharts / TradingView 类前端。
 *
 * @param period     day / week / month
 * @param adjustFlag 复权标记，取数据中实际的口径
 */
public record KlineSeriesVO(
        String code,
        String name,
        String period,
        Integer adjustFlag,
        LocalDate startDate,
        LocalDate endDate,
        int count,
        List<KlinePointVO> bars) {
}
