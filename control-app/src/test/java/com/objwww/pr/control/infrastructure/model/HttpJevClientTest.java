package com.objwww.pr.control.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.JevClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JE-01：TypeSafe /v1/systemone 契约测试（本机 HttpServer + 假传输，零外网）。
 * 对齐 jev-lab Provider：noul 信封（id 入 candidate_id）、answers 全覆盖、
 * 概率 ∈ [0,1]、模型版本一致、usage=input/output_tokens、usage 缺失不猜零。
 */
class HttpJevClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    private HttpJevClient clientAt(String baseUrl) {
        return new HttpJevClient(MAPPER, baseUrl, "test-key", "jev-1.13.0", 5_000);
    }

    private HttpJevClient clientWith(HttpJevClient.Transport transport) {
        return new HttpJevClient(MAPPER, "https://typesafe.example/v1", "test-key",
                "jev-1.13.0", java.time.Duration.ofMillis(5_000), transport);
    }

    /** 本机钉版端点：捕获请求头/体，回放给定响应（status, body） */
    private String startServer(int status, String responseBody,
            AtomicReference<String> capturedBody,
            AtomicReference<String> capturedAuth) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/systemone", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static JevClient.JevRequest request(String... ids) {
        Map<String, String> questions = new LinkedHashMap<>();
        for (String id : ids) {
            questions.put(id, "state.evidence item");
        }
        return new JevClient.JevRequest("jev-1.13.0",
                Map.of("objective", "调查 checkout 高错误率"), questions);
    }

    @Test
    void 成功响应解析为概率与usage_请求契约钉版() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        String base = startServer(200, """
                {"model":"jev-1.13.0",
                 "answers":{"e1":{"type":"noul","noul":0.9},"e2":{"type":"noul","noul":0.1}},
                 "usage":{"input_tokens":1234,"output_tokens":0}}
                """, body, auth);
        JevClient.JevAnswer answer = clientAt(base).score(request("e1", "e2"));

        assertThat(answer.probabilities()).containsEntry("e1", 0.9d)
                .containsEntry("e2", 0.1d);
        assertThat(answer.model()).isEqualTo("jev-1.13.0");
        assertThat(answer.usage().inputTokens()).isEqualTo(1234L);
        assertThat(answer.usage().outputTokens()).isEqualTo(0L);
        assertThat(answer.usage().totalTokens()).isEqualTo(1234L);
        assertThat(answer.usage().usageMissing()).isFalse();
        // 请求契约：Bearer key 入头、/v1/systemone 端点、model/state/questions 三键、
        // noul 信封 id 入 instructions.candidate_id（键不参与模型推理）
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        JsonNode requestJson = readTreeSafe(body.get());
        assertThat(requestJson.get("model").asText()).isEqualTo("jev-1.13.0");
        assertThat(requestJson.get("state").get("objective").asText())
                .contains("checkout");
        JsonNode question = requestJson.get("questions").get("e1");
        assertThat(question.get("type").asText()).isEqualTo("noul");
        assertThat(question.get("instructions").get("candidate_id").asText())
                .isEqualTo("e1");
        assertThat(question.get("instructions").get("question").asText())
                .contains("candidate_id");
        assertThat(question.get("criteria").get("true").asText()).isNotBlank();
        assertThat(requestJson.get("questions").get("e2")).isNotNull();
    }

    @Test
    void 答案缺失未知ID_契约拒绝() {
        AtomicReference<String> ignored1 = new AtomicReference<>();
        AtomicReference<String> ignored2 = new AtomicReference<>();
        String base = startServer(200, """
                {"model":"jev-1.13.0","answers":{"e1":{"type":"noul","noul":0.9}}}
                """, ignored1, ignored2);
        assertThatThrownBy(() -> clientAt(base).score(request("e1", "e2")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.CONTRACT);
    }

    @Test
    void 概率越界或缺失_契约拒绝() {
        AtomicReference<String> ignored1 = new AtomicReference<>();
        AtomicReference<String> ignored2 = new AtomicReference<>();
        String base = startServer(200, """
                {"model":"jev-1.13.0","answers":{"e1":{"type":"noul","noul":1.5}}}
                """, ignored1, ignored2);
        HttpJevClient target = clientAt(base);
        assertThatThrownBy(() -> target.score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.CONTRACT);
    }

    @Test
    void 返回模型与钉版不一致_契约拒绝() {
        AtomicReference<String> ignored1 = new AtomicReference<>();
        AtomicReference<String> ignored2 = new AtomicReference<>();
        String base = startServer(200, """
                {"model":"jev-1.14.0","answers":{"e1":{"type":"noul","noul":0.5}}}
                """, ignored1, ignored2);
        assertThatThrownBy(() -> clientAt(base).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .hasMessageContaining("jev-1.14.0");
    }

    @Test
    void usage缺失_不猜零() {
        AtomicReference<String> ignored1 = new AtomicReference<>();
        AtomicReference<String> ignored2 = new AtomicReference<>();
        String base = startServer(200, """
                {"model":"jev-1.13.0","answers":{"e1":{"type":"noul","noul":0.5}}}
                """, ignored1, ignored2);
        JevClient.JevAnswer answer = clientAt(base).score(request("e1"));
        assertThat(answer.usage().usageMissing()).isTrue();
        assertThat(answer.usage().totalTokens()).isNull();
    }

    @Test
    void http错误码封闭映射_零正文外泄() {
        assertThatThrownBy(() -> clientWith(new StatusTransport(429)).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.HTTP_429);
        assertThatThrownBy(() -> clientWith(new StatusTransport(503)).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.HTTP_5XX);
        assertThatThrownBy(() -> clientWith(new StatusTransport(401)).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.HTTP_4XX);
    }

    @Test
    void 传输异常映射NETWORK_超时映射TIMEOUT() throws Exception {
        assertThatThrownBy(() -> clientWith(new FailingTransport(
                new RuntimeException("connection reset"))).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.NETWORK);
        assertThatThrownBy(() -> clientWith(new FailingTransport(
                new java.net.http.HttpTimeoutException("timeout"))).score(request("e1")))
                .isInstanceOf(JevClient.JevClientException.class)
                .extracting(e -> ((JevClient.JevClientException) e).code())
                .isEqualTo(JevClient.JevClientException.TIMEOUT);
    }

    /** 固定状态码假传输（响应体空） */
    private static final class StatusTransport implements HttpJevClient.Transport {
        private final int status;

        StatusTransport(int status) {
            this.status = status;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            return new FixedResponse(status, "{}", request);
        }
    }

    /** 抛异常假传输 */
    private static final class FailingTransport implements HttpJevClient.Transport {
        private final Exception failure;

        FailingTransport(Exception failure) {
            this.failure = failure;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) throws Exception {
            throw failure;
        }
    }

    /** java.net.http.HttpResponse 接口的最小实现 */
    private static final class FixedResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private final HttpRequest request;

        FixedResponse(int status, String body, HttpRequest request) {
            this.status = status;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        }
        @Override public String body() { return body; }
        @Override public java.net.URI uri() { return null; }
        @Override public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_2;
        }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
