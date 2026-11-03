package com.li.stock.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码，由全局异常处理器统一转换为 {@link ApiResponse}。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static BizException badRequest(String message) {
        return new BizException(HttpStatus.BAD_REQUEST, message);
    }

    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, message);
    }
}
