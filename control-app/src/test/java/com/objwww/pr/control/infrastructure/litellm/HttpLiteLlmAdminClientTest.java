package com.objwww.pr.control.infrastructure.litellm;

import com.objwww.pr.control.eval.domain.litellm.SpendRecord;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-25 适配器：契约按 spike Exp5 实测（litellm 1.89.0）——/key/generate 返回 {"key":...}，
 * /spend/logs 兼容 {data:[...]} 壳；非 2xx 只报状态码不回显错误体（master key 域）。
 */
class HttpLiteLlmAdminClientTest {

    private HttpServer server;

    private String start(String path, String responseJson, int status,
                         AtomicReference<String> authHeader, AtomicReference<String> reqBody) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (reqBody != null) {
                    reqBody.set(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                }
                byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("mintRunKey：Bearer master key + key_alias/max_budget 契约，解析返回 key")
    void mintRunKeyParsesKey() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String base = start("/key/generate",
                "{\"key\":\"sk-vk-abc123\",\"key_alias\":\"eval-run-1\"}", 200, auth, body);

        HttpLiteLlmAdminClient client = new HttpLiteLlmAdminClient(base, "sk-master-1");
        String key = client.mintRunKey("eval-run-1", new java.math.BigDecimal("0.5"));

        assertThat(key).isEqualTo("sk-vk-abc123");
        assertThat(auth.get()).isEqualTo("Bearer sk-master-1");
        assertThat(body.get()).contains("\"key_alias\":\"eval-run-1\"");
        assertThat(body.get()).contains("\"max_budget\":0.5");
    }

    @Test
    @DisplayName("spendLogs：{data:[...]} 壳防御解析；缺 token 行落 null")
    void spendLogsParsesDataEnvelope() {
        String row = """
                {"request_id":"req-1","model":"deepseek-v3","spend":0.0029,
                 "prompt_tokens":100,"completion_tokens":20,"status":"success",
                 "metadata":{"user_api_key_alias":"run-x",
                   "spend_logs_metadata":{"run_id":"r1","attempt_id":"a1"}}}""";
        String base = start("/spend/logs", "{\"data\":[" + row + "]}", 200,
                new AtomicReference<>(), null);

        List<SpendRecord> rows = new HttpLiteLlmAdminClient(base, "sk-master-1").spendLogs();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keyAlias()).isEqualTo("run-x");
        assertThat(rows.get(0).metadataRunId()).isEqualTo("r1");
        assertThat(rows.get(0).promptTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("非 2xx：IllegalStateException 只带状态码，不回显错误体")
    void non2xxThrowsWithoutEchoingBody() {
        String base = start("/spend/logs",
                "{\"error\":{\"message\":\"Unauthorized sk-master-leak\"}}", 401,
                new AtomicReference<>(), null);

        HttpLiteLlmAdminClient client = new HttpLiteLlmAdminClient(base, "sk-master-1");
        assertThatThrownBy(client::spendLogs)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("sk-master-leak");
    }

    @Test
    @DisplayName("master key 缺失 = 装配错误 fail-fast（INV-AM3-3：仅 env 注入）")
    void missingMasterKeyFailsFast() {
        assertThatThrownBy(() -> new HttpLiteLlmAdminClient("http://127.0.0.1:1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("master key");
    }
}
