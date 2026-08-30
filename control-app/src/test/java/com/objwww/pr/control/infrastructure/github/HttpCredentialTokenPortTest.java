package com.objwww.pr.control.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读 token 窄接口客户端（T14）：共享密钥头随行、200 解 token、非 200 fail-closed，
 * 异常消息不泄露 token/密钥。
 */
class HttpCredentialTokenPortTest {

    private HttpServer server;
    private final AtomicReference<String> seenSecretHeader = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{\"token\":\"readonly-tok-1\"}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tokens/readonly", exchange -> {
            seenSecretHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            seenQuery.set(exchange.getRequestURI().getQuery());
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

    private HttpCredentialTokenPort port() {
        return new HttpCredentialTokenPort(
                "http://127.0.0.1:" + server.getAddress().getPort(), "s3cret",
                HttpClient.newHttpClient(), new ObjectMapper());
    }

    @Test
    void fetchesTokenWithSecretHeaderAndInstallationId() {
        String token = port().requestReadOnlyToken(4242L);

        assertThat(token).isEqualTo("readonly-tok-1");
        assertThat(seenSecretHeader.get()).isEqualTo("s3cret");
        assertThat(seenQuery.get()).isEqualTo("installation_id=4242");
    }

    @Test
    void non200FailsClosedWithoutLeaking() {
        statusToReturn = 401;
        bodyToReturn = "{\"error\":\"unauthorized\",\"echo\":\"s3cret\"}";

        assertThatThrownBy(() -> port().requestReadOnlyToken(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("s3cret"); // 响应体不进异常消息
    }

    @Test
    void missingTokenFieldFailsClosed() {
        bodyToReturn = "{\"unexpected\":true}";

        assertThatThrownBy(() -> port().requestReadOnlyToken(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }
}
