package com.li.stock.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 接口调用日志：每个 /api/** 请求在进入和返回时各打印一条，
 * 内容包含请求 ID、方法、路径、查询参数、请求体（POST）、客户端 IP、耗时与状态码。
 * <p>只做日志，不参与任何业务处理，也不改变请求/响应内容。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ApiLoggingFilter extends OncePerRequestFilter {

    /** 独立 logger 名称，方便在配置里单独控制：logging.level.API_ACCESS=INFO */
    private static final Logger log = LoggerFactory.getLogger("API_ACCESS");

    private static final String API_PREFIX = "/api";
    private static final int MAX_BODY_LEN = 800;
    /** 请求体缓存上限，超过部分不进日志 */
    private static final int CACHE_LIMIT = 4096;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, CACHE_LIMIT);

        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = query == null ? uri : uri + "?" + query;

        log.info("[{}] 请求开始  {} {}  客户端IP={}", reqId, request.getMethod(), target, clientIp(request));

        try {
            chain.doFilter(wrapped, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            log.error("[" + reqId + "] 接口执行异常 " + request.getMethod() + " " + target, ex);
            throw ex;
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            String body = requestBody(wrapped);
            String line = String.format("[%s] 请求结束  HTTP %d  %s %s  耗时=%dms%s",
                    reqId, status, request.getMethod(), target, costMs,
                    body.isEmpty() ? "" : "  请求体=" + body);

            if (status >= 500) {
                log.error(line);
            } else if (status >= 400) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }

    /** 只打印 POST/PUT 这类带体的请求，且截断，避免日志被大报文刷屏。 */
    private String requestBody(ContentCachingRequestWrapper request) {
        byte[] buf = request.getContentAsByteArray();
        if (buf.length == 0) {
            return "";
        }
        String body = new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (body.length() > MAX_BODY_LEN) {
            return body.substring(0, MAX_BODY_LEN) + "...(已截断)";
        }
        return body;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
