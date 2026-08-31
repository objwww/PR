package com.objwww.pr.publisher.infrastructure.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * installation token 铸造客户端：收窄 permissions（E6 三档 scope）+ repositories、
 * 解析 token/expires_at；非 201 fail-closed 且异常不带响应体（防 token 类内容入日志）。
 */
class HttpInstallationTokenClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private volatile int statusToReturn = 201;
    private volatile String bodyToReturn =
            "{\"token\":\"minted-tok\",\"expires_at\":\"2030-01-01T00:00:00Z\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/installations/", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenPath.set(exchange.getRequestURI().getPath());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToReturn, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpInstallationTokenClient client() {
        return new HttpInstallationTokenClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient(), objectMapper);
    }

    @Test
    void mintsWithNarrowedPermissionsAndRepositories() throws Exception {
        InstallationTokenClient.MintedToken minted = client().mint(
                4242L, TokenScope.CHECKS_WRITE, List.of("repo-a"), "test-jwt");

        assertThat(minted.token()).isEqualTo("minted-tok");
        assertThat(minted.expiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(seenAuth.get()).isEqualTo("Bearer test-jwt");
        assertThat(seenPath.get()).isEqualTo("/app/installations/4242/access_tokens");
        Map<String, Object> body = objectMapper.readValue(seenBody.get(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(body.get("permissions")).isEqualTo(Map.of("checks", "write"));
        assertThat(body.get("repositories")).isEqualTo(List.of("repo-a"));
    }

    @Test
    void scopeMappingFollowsE6() {
        assertThat(HttpInstallationTokenClient.permissionsOf(TokenScope.CHECKS_WRITE))
                .isEqualTo(Map.of("checks", "write"));
        assertThat(HttpInstallationTokenClient.permissionsOf(TokenScope.PULL_REQUESTS_WRITE))
                .isEqualTo(Map.of("pull_requests", "write"));
        assertThat(HttpInstallationTokenClient.permissionsOf(TokenScope.READ))
                .isEqualTo(Map.of("contents", "read", "pull_requests", "read", "checks", "read"));
    }

    @Test
    void emptyRepositoriesMeansInstallationWide() throws Exception {
        client().mint(1L, TokenScope.READ, List.of(), "test-jwt");

        Map<String, Object> body = objectMapper.readValue(seenBody.get(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(body).doesNotContainKey("repositories");
        assertThat(body.get("permissions"))
                .isEqualTo(Map.of("contents", "read", "pull_requests", "read", "checks", "read"));
    }

    @Test
    void non201FailsClosedWithoutResponseBody() {
        statusToReturn = 403;
        bodyToReturn = "{\"message\":\"private stuff minted-tok\"}";

        assertThatThrownBy(() -> client().mint(1L, TokenScope.READ, List.of(), "test-jwt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageNotContaining("private stuff"); // 响应体不进异常消息
    }
}
