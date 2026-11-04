package com.li.stock.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/**
 * BaoStock 原生 TCP 客户端。
 * Python 项目中的 baostock 包本质上也是通过 public-api.baostock.com:10030
 * 使用同一套消息协议；这里用 JDK Socket、CRC32 和 Jackson 直接实现登录、股票列表、
 * 历史日线查询及分页，页面触发时不再启动 Python 进程。
 *
 * <p>容错策略：BaoStock 服务端在限流或网络抖动时会中途断开连接，一旦某次响应
 * 读取超时/中断，Socket 里会残留该响应的后续字节，之后所有请求读到的“响应头”
 * 都是残留数据（表现为持续的“响应头格式错误”）。因此任何传输层异常都视为
 * 连接已损坏：立即关闭并重新登录，再重试当前请求（最多 {@link #MAX_ATTEMPTS} 次，
 * 指数退避）。分页查询按页重试，已成功的页不会重复请求。</p>
 */
public final class BaoStockClient implements AutoCloseable {
    private static final String VERSION = "00.9.40";
    private static final String SEP = "\1";
    private static final String HOST = "public-api.baostock.com";
    private static final int PORT = 10030;
    private static final int HEADER_LENGTH = 21;
    private static final int PAGE_SIZE = 2000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 2000;
    private static final byte[] COMPRESSED_END = "<![CDATA[]]>\n".getBytes(StandardCharsets.UTF_8);
    private static final String HISTORY_FIELDS =
            "date,open,high,low,close,preclose,volume,amount,adjustflag,turn,"
                    + "tradestatus,pctChg,isST,peTTM,pbMRQ,psTTM,pcfNcfTTM";

    private final ObjectMapper objectMapper;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private Socket socket;
    private String userId = "anonymous";

    public BaoStockClient(ObjectMapper objectMapper, int connectTimeoutMillis, int readTimeoutMillis) {
        this.objectMapper = objectMapper;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * 登录（带重试）。BaoStock 服务端高峰期登录响应也会超时，单次超时不应
     * 直接让整个抓取任务失败，这里与查询一样按指数退避重试。
     */
    public void login() {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                loginOnce();
                return;
            } catch (TransportException ex) {
                last = ex;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw new IllegalStateException("BaoStock 登录重试 " + MAX_ATTEMPTS + " 次仍失败："
                + (last == null ? "unknown" : rootMessage(last)), last);
    }

    /** 单次登录尝试，失败即抛 TransportException，由调用方决定是否重试。 */
    private void loginOnce() {
        closeSilently();
        try {
            Socket fresh = new Socket();
            fresh.connect(new InetSocketAddress(HOST, PORT), connectTimeoutMillis);
            fresh.setSoTimeout(readTimeoutMillis);
            socket = fresh;
            Response response = rawRequest("00", "login" + SEP + userId + SEP + "123456" + SEP + "0");
            response.requireSuccess();
            if (response.parts().size() > 3 && !response.parts().get(3).isBlank()) {
                userId = response.parts().get(3);
            }
        } catch (IOException ex) {
            closeSilently();
            throw new TransportException("BaoStock 连接失败：" + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            closeSilently();
            throw ex;
        }
    }

    /** 查询指定日期最近一次可用的沪深 A 股列表。 */
    public List<Map<String, String>> queryAllStock(LocalDate date) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Response response = request("35", String.join(SEP,
                    "query_all_stock", userId, String.valueOf(page), String.valueOf(PAGE_SIZE),
                    date.toString()));
            response.requireSuccess();
            List<Map<String, String>> pageRows = response.rows(6, 8);
            rows.addAll(pageRows);
            if (pageRows.size() < PAGE_SIZE) return rows;
        }
    }

    /** 查询一只股票一段日期的日线，返回字段名到原始字符串的映射。 */
    public List<Map<String, String>> queryHistory(String secid, LocalDate from, LocalDate to,
                                                   String adjustFlag) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Response response = request("95", String.join(SEP,
                    "query_history_k_data_plus", userId, String.valueOf(page), String.valueOf(PAGE_SIZE),
                    secid, HISTORY_FIELDS, from.toString(), to.toString(), "d", adjustFlag));
            response.requireSuccess();
            List<Map<String, String>> pageRows = response.rows(6, 8);
            rows.addAll(pageRows);
            if (pageRows.size() < PAGE_SIZE) return rows;
        }
    }

    /**
     * 带重试的请求：连接断开时自动重新登录，传输层异常按指数退避重试。
     * 业务错误（BaoStock 返回非 0 错误码）不重试，直接抛出。
     */
    private Response request(String messageType, String body) {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (socket == null || socket.isClosed()) {
                    // 用单次登录，重试节奏由本方法统一控制，避免与 login() 嵌套放大。
                    loginOnce();
                }
                return rawRequest(messageType, body);
            } catch (TransportException ex) {
                last = ex;
                closeSilently();
                if (attempt < MAX_ATTEMPTS) {
                    backoff(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw new IllegalStateException("BaoStock 重试 " + MAX_ATTEMPTS + " 次仍失败："
                + (last == null ? "unknown" : rootMessage(last)), last);
    }

    /** 单次请求，不做重试；任何 IO 异常都代表连接已不可复用。 */
    private Response rawRequest(String messageType, String body) {
        if (socket == null || socket.isClosed()) {
            throw new TransportException("BaoStock 尚未登录", null);
        }
        try {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String header = VERSION + SEP + messageType + SEP + String.format("%010d", bodyBytes.length);
            byte[] headBody = (header + body).getBytes(StandardCharsets.UTF_8);
            CRC32 crc32 = new CRC32();
            crc32.update(headBody);
            byte[] request = (header + body + SEP + crc32.getValue() + "\n").getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(request);
            output.flush();

            InputStream input = socket.getInputStream();
            byte[] responseHeader = readFully(input, HEADER_LENGTH);
            String headerText = new String(responseHeader, StandardCharsets.UTF_8);
            int firstSeparator = headerText.indexOf('\1');
            int secondSeparator = headerText.indexOf('\1', firstSeparator + 1);
            if (firstSeparator < 0 || secondSeparator < 0) throw new IOException("BaoStock 响应头格式错误");
            String responseType = headerText.substring(firstSeparator + 1, secondSeparator);
            /*
             * BaoStock Python 客户端不是按 header 中的长度读取响应，而是一直读到
             * <![CDATA[]]>\n。原因是部分非压缩响应的长度按字符数计算，中文股票名称
             * 转成 UTF-8 后字节数会更大；按字节数读取会截断 JSON，并把剩余内容当成
             * 下一次响应头。压缩响应也统一使用这个结束标记，因此这里按官方客户端
             * 的方式读取完整帧，再对压缩正文解压。
             */
            byte[] framedBody = readUntil(input, COMPRESSED_END);
            if (framedBody.length < COMPRESSED_END.length) {
                throw new IOException("BaoStock 响应缺少结束标记");
            }
            byte[] bodyBytesResponse = Arrays.copyOf(framedBody,
                    framedBody.length - COMPRESSED_END.length);
            boolean compressed = "96".equals(responseType);
            if (compressed) {
                bodyBytesResponse = inflate(bodyBytesResponse);
            }
            String responseBody = new String(bodyBytesResponse, StandardCharsets.UTF_8);
            List<String> parts = List.of(responseBody.split(SEP, -1));
            return new Response(responseType, parts, objectMapper);
        } catch (IOException | NumberFormatException ex) {
            // 传输层异常后流的位置不可知，连接必须丢弃，由上层重连。
            closeSilently();
            throw new TransportException("BaoStock 查询失败：" + ex.getMessage(), ex);
        }
    }

    private byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private byte[] readFully(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new IOException("BaoStock 连接提前关闭");
            offset += count;
        }
        return data;
    }

    private byte[] readUntil(InputStream input, byte[] marker) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int matched = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) throw new IOException("BaoStock 连接提前关闭");
            for (int index = 0; index < count; index++) {
                byte current = buffer[index];
                output.write(current);
                if (current == marker[matched]) {
                    matched++;
                    if (matched == marker.length) return output.toByteArray();
                } else {
                    matched = current == marker[0] ? 1 : 0;
                }
            }
        }
    }

    private void backoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BaoStock 重试等待被中断", ex);
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        if (socket == null || socket.isClosed()) return;
        try {
            rawRequest("02", "logout" + SEP + userId + SEP
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        } catch (Exception ignored) {
            // 网络异常时仍然关闭本地 Socket。
        } finally {
            closeSilently();
        }
    }

    private void closeSilently() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 传输层异常：连接断开、读写超时、响应错位等；触发重连与重试。 */
    private static final class TransportException extends RuntimeException {
        private TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Response(String messageType, List<String> parts, ObjectMapper objectMapper) {
        private void requireSuccess() {
            if (parts.size() < 2 || !"0".equals(parts.get(0))) {
                String message = parts.size() > 1 ? parts.get(1) : "未知错误";
                throw new IllegalStateException("BaoStock 返回错误："
                        + (parts.isEmpty() ? "unknown" : parts.get(0)) + " " + message);
            }
        }

        private List<Map<String, String>> rows(int dataIndex, int fieldsIndex) {
            requireSuccess();
            if (parts.size() <= fieldsIndex || parts.get(dataIndex).isBlank()) return List.of();
            String[] fields = parts.get(fieldsIndex).split(",", -1);
            try {
                JsonNode records = objectMapper.readTree(parts.get(dataIndex)).path("record");
                List<Map<String, String>> result = new ArrayList<>();
                for (JsonNode row : records) {
                    Map<String, String> item = new LinkedHashMap<>();
                    for (int i = 0; i < fields.length && i < row.size(); i++) {
                        item.put(fields[i].trim(), row.get(i).asText());
                    }
                    result.add(item);
                }
                return result;
            } catch (IOException ex) {
                throw new IllegalStateException("BaoStock 返回数据解析失败：" + ex.getMessage(), ex);
            }
        }
    }
}
