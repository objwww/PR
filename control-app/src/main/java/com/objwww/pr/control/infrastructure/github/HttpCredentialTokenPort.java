package com.objwww.pr.control.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.port.CredentialTokenPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 只读 token 窄接口客户端（评审修正 #6：Control↔Publisher 唯一直连点，T14）。
 * 调 Publisher 的 {@code GET /internal/tokens/readonly?installation_id=}，
 * 共享密钥头 {@code X-Internal-Token} 校验（MVP 形态 P8：loopback/内网别名 + 共享密钥）。
 *
 * <p>纪律（I2）：本类只暴露只读 token 申请，不存在任何写 scope 方法（T16 ArchUnit 查）；
 * token 只存内存、不进异常消息与日志。
 */
public class HttpCredentialTokenPort implements CredentialTokenPort {

    public static final String SECRET_HEADER = "X-Internal-Token";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String endpointBase;
    private final String sharedSecret;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public HttpCredentialTokenPort(String endpointBase, String sharedSecret) {
        this(endpointBase, sharedSecret, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    /** 测试可注入 HttpClient/ObjectMapper */
    public HttpCredentialTokenPort(String endpointBase, String sharedSecret,
                                   HttpClient http, ObjectMapper objectMapper) {
        this.endpointBase = Objects.requireNonNull(endpointBase, "endpointBase");
        this.sharedSecret = Objects.requireNonNull(sharedSecret, "sharedSecret");
        this.http = Objects.requireNonNull(http);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public String requestReadOnlyToken(long installationId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        endpointBase + "/internal/tokens/readonly?installation_id=" + installationId))
                .timeout(REQUEST_TIMEOUT)
                .header(SECRET_HEADER, sharedSecret)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("只读 token 窄接口调用失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("只读 token 窄接口调用被中断", e);
        }
        if (response.statusCode() != 200) {
            // 不带响应体：防对端错误页意外回显敏感内容
            throw new IllegalStateException("只读 token 窄接口非 200: " + response.statusCode());
        }
        try {
            Map<String, Object> body = objectMapper.readValue(response.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            Object token = body.get("token");
            if (token == null || token.toString().isBlank()) {
                throw new IllegalStateException("只读 token 窄接口响应缺 token 字段");
            }
            return token.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("只读 token 响应解析失败", e);
        }
    }
}
