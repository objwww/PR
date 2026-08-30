package com.objwww.pr.publisher.infrastructure.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * installation token 铸造的 HTTP 实现（JDK HttpClient）：
 * POST {apiBase}/app/installations/{id}/access_tokens，
 * body 收窄 permissions（E6：checks:write / pull_requests:write / contents:read）
 * + 可选 repositories 收窄。
 *
 * <p>纪律：token 只进内存返回值；非 201 的异常消息只带状态码，不带响应体
 * （防对端错误内容意外带入日志）。
 */
public class HttpInstallationTokenClient implements InstallationTokenClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String API_VERSION = "2022-11-28";

    private final String apiBase;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public HttpInstallationTokenClient(String apiBase) {
        this(apiBase, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    /** 测试可注入 HttpClient/ObjectMapper */
    public HttpInstallationTokenClient(String apiBase, HttpClient http, ObjectMapper objectMapper) {
        this.apiBase = Objects.requireNonNull(apiBase);
        this.http = Objects.requireNonNull(http);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public MintedToken mint(long installationId, TokenScope scope, List<String> repositories,
                            String appJwt) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(appJwt, "appJwt");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", permissionsOf(scope));
        if (repositories != null && !repositories.isEmpty()) {
            body.put("repositories", repositories);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(
                            URI.create(apiBase + "/app/installations/" + installationId + "/access_tokens"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + appJwt)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("installation token 请求体序列化失败", e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("installation token 铸造请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("installation token 铸造被中断", e);
        }
        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "installation token 铸造非 201: " + response.statusCode()
                            + "（installation=" + installationId + ", scope=" + scope + "）");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            Object token = parsed.get("token");
            Object expiresAt = parsed.get("expires_at");
            if (token == null || expiresAt == null) {
                throw new IllegalStateException("installation token 响应缺 token/expires_at 字段");
            }
            return new MintedToken(token.toString(), Instant.parse(expiresAt.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("installation token 响应解析失败", e);
        }
    }

    /** scope → GitHub permissions（E6：写最小化，只读 = contents:read） */
    static Map<String, String> permissionsOf(TokenScope scope) {
        return switch (scope) {
            case CHECKS_WRITE -> Map.of("checks", "write");
            case PULL_REQUESTS_WRITE -> Map.of("pull_requests", "write");
            case READ -> Map.of("contents", "read");
        };
    }
}
