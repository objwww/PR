package com.objwww.pr.duty.webhook;

import com.objwww.pr.duty.http.JsonHttp;
import com.objwww.pr.duty.snapshot.SnapshotState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直发客户端锚（M7-17 业务码纪律 E-21）：HTTP 200 + errcode≠0 = 失败（防静默假成功）；
 * errcode=0 = 送达；HTTP≠2xx = 失败；env 缺失 = 确定性失败；钉钉加签向量独立复算。
 */
class DutyWebhookClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 本地假 webhook：按 path 回固定 JSON（"HTTP_xxx" 特殊值回纯状态码） */
    private String startServer(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            boolean statusOnly = response.startsWith("HTTP_");
            int status = statusOnly ? Integer.parseInt(response.substring(5)) : 200;
            byte[] out = statusOnly ? "{}".getBytes(StandardCharsets.UTF_8)
                    : response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static SnapshotState.Channel chan() {
        return new SnapshotState.Channel("bot", "DINGTALK", 1, false,
                "K_TEST_WEBHOOK", null);
    }

    /** env seam 子类：K_TEST_WEBHOOK → 本地假 webhook URL */
    static final class FixedEnvClient extends DutyWebhookClient {
        private final String url;

        FixedEnvClient(String url) {
            super(new JsonHttp());
            this.url = url;
        }

        @Override
        protected String env(String key) {
            return "K_TEST_WEBHOOK".equals(key) ? url : super.env(key);
        }
    }

    @Test
    @DisplayName("errcode=0 HTTP 200 → 送达；请求体 msgtype=text 含文案")
    void errcodeZeroMeansDelivered() throws IOException {
        String url = startServer("{\"errcode\":0,\"errmsg\":\"ok\"}");
        FixedEnvClient client = new FixedEnvClient(url);

        DutyWebhookClient.SendResult r = client.send(chan(), "半夜告警：控制面失明");

        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("ok");
        assertThat(lastBody.get()).contains("半夜告警：控制面失明")
                .contains("\"msgtype\":\"text\"");
    }

    @Test
    @DisplayName("业务码陷阱：HTTP 200 + errcode=45009 → 失败 errcode_45009（不假成功）")
    void http200WithNonZeroErrcodeIsFailure() throws IOException {
        String url = startServer("{\"errcode\":45009,\"errmsg\":\"限流\"}");
        FixedEnvClient client = new FixedEnvClient(url);

        DutyWebhookClient.SendResult r = client.send(chan(), "x");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).startsWith("errcode_45009");
    }

    @Test
    @DisplayName("HTTP 500 → 失败 http_500")
    void httpErrorIsFailure() throws IOException {
        FixedEnvClient client = new FixedEnvClient(startServer("HTTP_500"));

        assertThat(client.send(chan(), "x").detail()).isEqualTo("http_500");
    }

    @Test
    @DisplayName("env 键缺失 → 确定性失败 env_missing（通道未配置语义）")
    void missingEnvIsDeterministicFailure() {
        FixedEnvClient client = new FixedEnvClient("http://unused");

        SnapshotState.Channel missing = new SnapshotState.Channel("bot", "DINGTALK", 1,
                false, "NO_SUCH_ENV_KEY_ANYWHERE", null);
        DutyWebhookClient.SendResult r = client.send(missing, "x");

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo("env_missing:NO_SUCH_ENV_KEY_ANYWHERE");
    }

    @Test
    @DisplayName("钉钉加签向量：独立复算 HMAC-SHA256 与 URL 形状")
    void dingTalkSignMatchesIndependentComputation() {
        String url = "https://oapi.dingtalk.com/robot/send?access_key=abc";
        long ts = 1757491200000L;
        String secret = "SEC_test";

        String signed = DutyWebhookClient.signedUrl(url, secret, ts);

        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = URLEncoder.encode(Base64.getEncoder().encodeToString(
                            mac.doFinal((ts + "\n" + secret).getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(signed).isEqualTo(url + "&timestamp=" + ts + "&sign=" + expected);
    }
}
