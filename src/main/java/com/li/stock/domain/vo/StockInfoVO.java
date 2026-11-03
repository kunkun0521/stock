package com.li.stock.domain.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票基础信息。
 */
public record StockInfoVO(
        String code,
        String name,
        Integer market,
        String secid,
        String board,
        String exchange,
        LocalDate ipoDate,
        LocalDate outDate,
        String stockType,
        String listingStatus,
        String industry,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
