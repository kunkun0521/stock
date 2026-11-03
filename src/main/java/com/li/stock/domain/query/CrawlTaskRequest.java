package com.li.stock.domain.query;

/** 页面触发日线抓取时的参数。 */
public record CrawlTaskRequest(
        Integer limit,
        Integer daysBack,
        Integer fqType,
        Boolean storeRawPrices,
        String codePrefixes,
        Integer requestDelayMillis,
        Boolean refreshStockList
) {}
