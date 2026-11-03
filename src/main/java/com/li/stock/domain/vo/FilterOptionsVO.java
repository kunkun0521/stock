package com.li.stock.domain.vo;

import java.util.List;

/**
 * 筛选下拉选项，避免前端硬编码板块 / 行业。
 */
public record FilterOptionsVO(
        List<String> boards,
        List<String> industries,
        List<String> exchanges,
        List<Option> statusOptions) {

    public record Option(int value, String label) {
    }
}
