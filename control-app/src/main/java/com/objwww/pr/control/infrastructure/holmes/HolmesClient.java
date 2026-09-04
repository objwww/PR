package com.objwww.pr.control.infrastructure.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HolmesGPT /api/chat 客户端（§6.5；官方 http-api.md 契约一手核对）。
 *
 * <p>两层超时：连接层快速失败（不可达立刻报）+ 读层封顶（长调查不无限挂起）——
 * 由 {@link SimpleClientHttpRequestFactory} 的 connectTimeout/readTimeout 实现。
 * 鉴权：X-API-Key 头（官方支持 X-API-Key 或 Bearer 两种）。
 *
 * <p>本类只做 HTTP 与 usage 元数据尽力解析，不做错误分类决策
 * （调用方用 {@link HolmesErrorClassifier}）与结构验证（EvidencePackageValidator）。
 * 零 Spring 事务注解——外部调用永不挂事务（AFT-A04）。
 */
public final class HolmesClient {

    /** Holmes 返回非 2xx（状态码 + 截断的响应体，脱敏由调用方负责） */
    public static final class HolmesHttpException extends RuntimeException {
        private final int status;
        private final String responseBody;

        public HolmesHttpException(int status, String responseBody) {
            super("Holmes HTTP " + status);
            this.status = status;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        public int status() {
            return status;
        }

        public String responseBody() {
            return responseBody;
        }
    }

    /** 网络/超时类失败（连接拒绝、读超时、连接重置等） */
    public static final class HolmesTransportException extends RuntimeException {
        private final boolean timeout;

        public HolmesTransportException(boolean timeout, Throwable cause) {
            super(timeout ? "Holmes 请求超时" : "Holmes 网络错误", cause);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }

    /** 一次成功调用的原始响应体 + tokens 尽力解析结果（官方 metadata.usage；缺失→usageMissing） */
    public record HolmesChatResult(String body, Integer promptTokens, Integer completionTokens,
                                   Integer totalTokens, boolean usageMissing) {
    }

    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxResponseBytes;

    public HolmesClient(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
                        int maxResponseBytes) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.rest = RestClient.builder()
                .baseUrl(normalized)
                .defaultHeader("X-API-Key", apiKey)
                .requestFactory(factory)
                .build();
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes 必须为正");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * POST /api/chat。
     *
     * @param requestBody 完整请求体 JSON（ask + response_format 等；不含任何密钥）
     */
    public HolmesChatResult chat(String requestBody) {
        String body;
        try {
            body = rest.post()
                    .uri("/api/chat")
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    // BA-12② 受限读：整包 body(String) 会被异常大响应打爆堆——限读到
                    // maxResponseBytes+1 即止，超限截断的文本交给结构验证链判 REJECTED_OVERSIZE
                    .exchange((request, response) -> {
                        String text = readBounded(response.getBody());
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return text;
                        }
                        throw new HolmesHttpException(response.getStatusCode().value(), truncate(text));
                    });
        } catch (RestClientResponseException e) {
            throw new HolmesHttpException(e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            throw new HolmesTransportException(isTimeout(e), e);
        } catch (RestClientException e) {
            // 读超时可能发生在状态行/响应体提取阶段，Spring 包成通用 RestClientException
            // （cause 链里的 SocketTimeoutException 才是真相）——同样归 transport 分类
            throw new HolmesTransportException(isTimeout(e), e);
        }
        if (body == null) {
            // 2xx 但空体：按传输异常归类（Holmes 不应返回空 200；validator 也会拒绝空体）
            throw new HolmesTransportException(false, new IOException("Holmes 返回空响应体"));
        }
        return new HolmesChatResult(body,
                usageField(body, "prompt_tokens"), usageField(body, "completion_tokens"),
                usageField(body, "total_tokens"), usageMissing(body));
    }

    /** metadata.usage.{prompt,completion,total}_tokens 尽力解析（官方字段；缺失/非整型→null） */
    private Integer usageField(String body, String field) {
        JsonNode usage = usageNode(body);
        if (usage == null || !usage.has(field) || !usage.get(field).isInt()) {
            return null;
        }
        return usage.get(field).asInt();
    }

    /** 受限读：最多读 maxResponseBytes+1 字节（+1 探测超限），UTF-8 解码 */
    private String readBounded(InputStream in) throws IOException {
        long limit = (long) maxResponseBytes + 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        while (total < limit) {
            int want = (int) Math.min(chunk.length, limit - total);
            int n = in.read(chunk, 0, want);
            if (n < 0) {
                break;
            }
            buffer.write(chunk, 0, n);
            total += n;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private boolean usageMissing(String body) {
        return usageNode(body) == null
                || usageNode(body).path("prompt_tokens").asInt(-1) < 0
                || usageNode(body).path("completion_tokens").asInt(-1) < 0
                || usageNode(body).path("total_tokens").asInt(-1) < 0;
    }

    private JsonNode usageNode(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            return root.path("metadata").path("usage");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
