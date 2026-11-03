package com.li.stock.common;

/**
 * 查询参数归一化：把外部传入的分页/条数参数收敛到安全范围，避免超大查询打爆数据库。
 */
public final class QuerySupport {

    public static final int MAX_PAGE_SIZE = 500;

    private QuerySupport() {
    }

    /** 页码从 1 开始，非法值回退到 1。 */
    public static int page(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    public static int size(Integer size, int defaultSize, int maxSize) {
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, maxSize);
    }

    public static int offset(int page, int size) {
        return (page - 1) * size;
    }

    /** 用于 K 线、排行榜这类“取最近 N 条”的场景。 */
    public static int limit(Integer limit, int defaultLimit, int maxLimit) {
        return size(limit, defaultLimit, maxLimit);
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean hasText(String value) {
        return trimToNull(value) != null;
    }
}
