package com.li.stock.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 接口访问日志：每个 /api/** 请求打印两行——
 * 进入时一行（方法/路径/客户端IP/请求体），结束时一行（状态码/耗时/响应大小）。
 * <p>只做日志，不参与任何业务处理；请求体用可重复读的包装类传给下游，
 * 响应内容原样写回客户端，不改变任何接口返回。</p>
 * <p>用独立 logger 名 {@code API_ACCESS}，可在配置里单独开关：logging.level.API_ACCESS=OFF</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ApiLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("API_ACCESS");

    private static final String API_PREFIX = "/api";
    /** 请求体缓存上限（字节），超过不读取、不打印 */
    private static final int CACHE_LIMIT = 8192;
    /** 日志里请求体最多打印的字符数 */
    private static final int MAX_LOG_LEN = 1500;
    /** 脱敏的表单字段 */
    private static final Set<String> SENSITIVE_PARAMS = Set.of("password", "token", "secret", "key");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        String method = request.getMethod();
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);

        // 1) 请求阶段：先把请求体读出来缓存，再用可重复读的包装类传给下游
        HttpServletRequest wrappedRequest = request;
        String bodyText = "";
        try {
            String contentType = request.getContentType();
            boolean form = contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
            if (form) {
                // 表单先触发容器解析参数，保证后面的 @RequestParam 仍能取到值
                bodyText = formText(request.getParameterMap());
            } else if (request.getContentLengthLong() <= CACHE_LIMIT) {
                byte[] body = request.getInputStream().readAllBytes();
                wrappedRequest = new CachedBodyRequestWrapper(request, body);
                bodyText = new String(body, charset(request));
            } else {
                bodyText = "(请求体超过 " + CACHE_LIMIT + " B，未读取打印)";
            }

            log.info("[{}] --> {} {} | IP={}{}", reqId, method, target, clientIp(request),
                    bodyText.isBlank() ? "" : " | 请求体: " + brief(bodyText));
        } catch (Exception e) {
            log.error("[{}] 请求日志打印异常（已降级为不缓存请求体，不影响接口）", reqId, e);
            wrappedRequest = request;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException ex) {
            log.error("[{}] 接口执行异常: {} {}", reqId, method, target, ex);
            throw ex;
        } finally {
            // 2) 先把缓存的响应内容原样写回真实响应，这一步必须最先做
            byte[] respBytes = wrappedResponse.getContentAsByteArray();
            try {
                wrappedResponse.copyBodyToResponse();
            } catch (IOException e) {
                log.error("[{}] 响应内容回写失败", reqId, e);
            }

            // 3) 日志整体兜底：打印日志出任何问题都不得影响接口返回
            try {
                long costMs = (System.nanoTime() - start) / 1_000_000;
                int status = wrappedResponse.getStatus();

                logEnd(status, "[{}] <-- {} {} | HTTP {} | 耗时={}ms | 响应大小={} B",
                        reqId, method, target, status, costMs, respBytes.length);
            } catch (Exception e) {
                log.error("[{}] 响应日志打印异常（不影响接口）", reqId, e);
            }
        }
    }

    /** 按状态码选择日志级别：5xx ERROR / 4xx WARN / 其它 INFO。 */
    private void logEnd(int status, String format, Object... args) {
        if (status >= 500) {
            log.error(format, args);
        } else if (status >= 400) {
            log.warn(format, args);
        } else {
            log.info(format, args);
        }
    }

    /** 表单 body 的参数等价形式，含脱敏。 */
    private String formText(Map<String, String[]> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((name, values) -> {
            for (String value : values) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                boolean masked = SENSITIVE_PARAMS.stream().anyMatch(k -> name.toLowerCase(Locale.ROOT).contains(k));
                sb.append(name).append('=').append(masked ? "***" : value);
            }
        });
        return sb.toString();
    }

    /** 压成单行并截断，避免大 JSON 刷屏。 */
    private String brief(String raw) {
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_LOG_LEN) {
            return text.substring(0, MAX_LOG_LEN) + "...(已截断，原文 " + text.length() + " 字符)";
        }
        return text;
    }

    private Charset charset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 可重复读取请求体的包装类：日志先把 body 读走了，这里基于缓存字节重新提供输入流，
     * 保证下游 @RequestBody 等仍能正常读取。
     */
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return source.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取，无需异步监听
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charsetOf((HttpServletRequest) getRequest())));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private static Charset charsetOf(HttpServletRequest request) {
            String encoding = request.getCharacterEncoding();
            if (encoding == null) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
