package com.li.stock.common;

import java.util.List;

/**
 * 通用分页结果。
 *
 * @param items 当前页数据
 * @param total 总条数
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param pages 总页数
 */
public record PageResult<T>(List<T> items, long total, int page, int size, int pages) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        int pages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(items, total, page, size, pages);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size, 0);
    }
}
