package com.li.stock.domain.vo;

import java.time.LocalDate;

/**
 * 数据覆盖范围，用于判断某只股票的历史数据是否完整。
 *
 * @param rowCount    记录总数（同一交易日可能有多个复权口径，故可能大于交易日数）
 * @param tradingDays 去重后的交易日数量
 */
public record DataRangeVO(
        String code,
        String name,
        LocalDate firstDate,
        LocalDate lastDate,
        long rowCount,
        long tradingDays,
        String adjustFlags,
        String dataSources) {
}
