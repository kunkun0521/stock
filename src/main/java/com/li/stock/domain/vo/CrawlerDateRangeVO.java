package com.li.stock.domain.vo;

import java.time.LocalDate;

/** 一只股票、一个复权口径在数据库中的最早和最新交易日。 */
public record CrawlerDateRangeVO(
        String code,
        Integer adjustFlag,
        LocalDate firstDate,
        LocalDate latestDate) {
}
