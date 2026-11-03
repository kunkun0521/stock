package com.li.stock.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 涨跌幅分布，用于画直方图。
 */
public record DistributionVO(LocalDate tradeDate, int total, List<Bucket> buckets) {

    /**
     * @param from  区间下限（含），null 表示不设下限
     * @param to    区间上限（不含），null 表示不设上限
     */
    public record Bucket(String label, BigDecimal from, BigDecimal to, int count) {
    }
}
