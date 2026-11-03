package com.li.stock.common;

/**
 * 统一响应结构，前端只需判断 code == 0。
 *
 * @param code      业务状态码，0 表示成功
 * @param message   提示信息
 * @param data      业务数据
 * @param timestamp 服务端时间戳（毫秒）
 */
public record ApiResponse<T>(int code, String message, T data, long timestamp) {

    public static final int SUCCESS = 0;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS, "ok", data, System.currentTimeMillis());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS, "ok", null, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis());
    }
}
