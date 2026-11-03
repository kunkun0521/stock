package com.li.stock.domain.vo;

/**
 * 轻量股票信息，用于搜索联想、下拉选择。
 */
public record StockBriefVO(
        String code,
        String name,
        Integer market,
        String exchange,
        String board,
        String industry) {
}
