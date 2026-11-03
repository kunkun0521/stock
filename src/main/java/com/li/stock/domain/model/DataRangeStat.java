package com.li.stock.domain.model;

import java.time.LocalDate;

/**
 * 某只股票的数据覆盖情况（仓储层原始结果）。
 *
 * @param rowCount    记录总数（同一交易日可能有多个复权口径）
 * @param tradingDays 去重后的交易日数量
 */
public record DataRangeStat(
        LocalDate firstDate,
        LocalDate lastDate,
        long rowCount,
        long tradingDays,
        String adjustFlags,
        String dataSources) {
}
