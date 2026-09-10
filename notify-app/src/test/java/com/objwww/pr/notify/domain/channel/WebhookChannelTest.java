package com.objwww.pr.notify.domain.channel;

import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-22 webhook 渠道：平台 body 形态（钉钉 title+text / 企微 content）、钉钉加签算法
 * 向量、传输结局分类（429 Retry-After 缺省/上限、5xx 可重试、4xx 终态、连接失败 vs
 * 结果未知的诚实分流）、无 webhook 拒绝创建（INV-AM3-3）。
 */
class WebhookChannelTest {

    private final RenderedNotification notification = new RenderedNotification(
            "标题", "正文", UUID.randomUUID());

    @Test
    @DisplayName("平台 body：钉钉 msgtype=markdown 带 title/text；企微只有 content")
    void buildsPlatformBodies() {
        WebhookChannel dingtalk = channel(WebhookChannel.Platform.DINGTALK,
                new RecordingTransport(List.of(new WebhookTransport.Response(200, null, "ok"))));
        WebhookChannel wecom = channel(WebhookChannel.Platform.WECOM,
                new RecordingTransport(List.of(new WebhookTransport.Response(200, null, "ok"))));

        assertThat(dingtalk.requestBody(notification))
                .contains("\"msgtype\":\"markdown\"").contains("\"title\":\"标题\"")
                .contains("\"text\":\"正文\"");
        assertThat(wecom.requestBody(notification))
                .contains("\"content\":\"标题\\n正文\"")
                .doesNotContain("\"title\"");
    }

    @Test
    @DisplayName("钉钉加签：HmacSHA256(secret, ts+\\n+secret) → Base64 → URL 编码并入 query")
    void signsDingTalkUrl() {
        String url = WebhookChannel.signed("https://oapi.dingtalk.com/robot/send?access_token=x",
                1700000000000L, "SEC secret");
        assertThat(url)
                .startsWith("https://oapi.dingtalk.com/robot/send?access_token=x&timestamp=1700000000000&sign=")
                // Base64 的 + 与 / 必须 URL 编码（%2B/%2F），query 里不留裸特殊字符
                .doesNotContain("+")
                .contains("2eHN5%2F097belHKe1VMoktGK%2FqNbj6Rqo57NoBeU1OQA%3D");
    }

    @Test
    @DisplayName("状态码分类：2xx 按 errcode 判；429 读 Retry-After（缺省 60s、上限 3600s）；5xx 可重试；4xx 终态")
    void classifiesStatuses() {
        assertThat(WebhookTransport.Classifier.fromStatus(200, null, "{\"errcode\":0}"))
                .isInstanceOf(NotificationChannel.SendResult.Delivered.class);
        assertThat(WebhookTransport.Classifier.fromStatus(204, null, "{\"errcode\":0}"))
                .isInstanceOf(NotificationChannel.SendResult.Delivered.class);

        var limited = (NotificationChannel.SendResult.RateLimited)
                WebhookTransport.Classifier.fromStatus(429, "120", "{}");
        assertThat(limited.retryAfterSeconds()).isEqualTo(120);

        var limitedDefault = (NotificationChannel.SendResult.RateLimited)
                WebhookTransport.Classifier.fromStatus(429, null, "{}");
        assertThat(limitedDefault.retryAfterSeconds()).isEqualTo(60);

        var limitedJunk = (NotificationChannel.SendResult.RateLimited)
                WebhookTransport.Classifier.fromStatus(429, "not-a-number", "{}");
        assertThat(limitedJunk.retryAfterSeconds()).isEqualTo(60);

        assertThat(WebhookTransport.Classifier.fromStatus(500, null, "{}"))
                .isInstanceOf(NotificationChannel.SendResult.Retryable.class);
        assertThat(WebhookTransport.Classifier.fromStatus(503, "300", "{}"))
                .isInstanceOf(NotificationChannel.SendResult.Retryable.class);
        assertThat(WebhookTransport.Classifier.fromStatus(400, null, "{}"))
                .isInstanceOf(NotificationChannel.SendResult.Permanent.class);
        assertThat(WebhookTransport.Classifier.fromStatus(401, null, "{}"))
                .isInstanceOf(NotificationChannel.SendResult.Permanent.class);
        assertThat(WebhookTransport.Classifier.fromStatus(404, null, "{}"))
                .isInstanceOf(NotificationChannel.SendResult.Permanent.class);
    }

    @Test
    @DisplayName("F20 业务码判定：HTTP 200 + errcode≠0 不标 SENT（→Retryable 带业务码）；errcode=0 才 Delivered")
    void f20BusinessCodeFaces() {
        var rejected = (NotificationChannel.SendResult.Retryable)
                WebhookTransport.Classifier.fromStatus(200, null,
                        "{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        assertThat(rejected.error()).contains("business_code_310000").contains("sign not match");

        assertThat(WebhookTransport.Classifier.fromStatus(200, null,
                        "{\"errcode\":0,\"errmsg\":\"ok\"}"))
                .isInstanceOf(NotificationChannel.SendResult.Delivered.class);

        // 回执形态不可证：2xx 但 body 非 errcode JSON（空/纯文本/异形）→ 结果未知不自动重发
        assertThat(WebhookTransport.Classifier.fromStatus(200, null, "ok"))
                .isInstanceOf(NotificationChannel.SendResult.OutcomeUnknown.class);
        assertThat(WebhookTransport.Classifier.fromStatus(200, null, ""))
                .isInstanceOf(NotificationChannel.SendResult.OutcomeUnknown.class);
        assertThat(WebhookTransport.Classifier.fromStatus(200, null,
                        "{\"foo\":\"no errcode field\"}"))
                .isInstanceOf(NotificationChannel.SendResult.OutcomeUnknown.class);

        // 渠道端到端：假 transport 回 200+errcode≠0 → send 结果必须不是 Delivered
        RecordingTransport lyingRobot = new RecordingTransport(List.of(
                new WebhookTransport.Response(200, null,
                        "{\"errcode\":130101,\"errmsg\":\"hint: limit exceeded\"}")));
        var viaChannel = (NotificationChannel.SendResult.Retryable)
                channel(WebhookChannel.Platform.DINGTALK, lyingRobot)
                        .send(notification, UUID.randomUUID());
        assertThat(viaChannel.error()).contains("business_code_130101");
    }

    @Test
    @DisplayName("传输异常诚实分流：连接失败=可重试；请求超时/响应中断=结果未知（不自动重发）")
    void classifiesTransportFailuresHonestly() {
        assertThat(WebhookTransport.Classifier.fromTransport(
                new java.net.http.HttpConnectTimeoutException("connect")))
                .isInstanceOf(NotificationChannel.SendResult.Retryable.class);
        assertThat(WebhookTransport.Classifier.fromTransport(
                new java.net.ConnectException("refused")))
                .isInstanceOf(NotificationChannel.SendResult.Retryable.class);
        assertThat(WebhookTransport.Classifier.fromTransport(
                new java.net.http.HttpTimeoutException("response timeout")))
                .isInstanceOf(NotificationChannel.SendResult.OutcomeUnknown.class);
        assertThat(WebhookTransport.Classifier.fromTransport(
                new java.io.IOException("connection reset")))
                .isInstanceOf(NotificationChannel.SendResult.OutcomeUnknown.class);
    }

    @Test
    @DisplayName("无 webhook 拒绝创建（INV-AM3-3：渠道凭证仅 env 注入，缺配置 fail-fast）")
    void rejectsBlankWebhook() {
        assertThatThrownBy(() -> new WebhookChannel(WebhookChannel.Platform.DINGTALK,
                " ", null, new RecordingTransport(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhook");
    }

    @Test
    @DisplayName("send：2xx → Delivered；传输失败包装为分类结果且 URL 带加签参数")
    void sendsViaTransportPort() {
        RecordingTransport ok = new RecordingTransport(
                List.of(new WebhookTransport.Response(200, null, "{\"errcode\":0}")));
        WebhookChannel signed = new WebhookChannel(WebhookChannel.Platform.DINGTALK,
                "https://oapi.test/robot", "SECtest", ok);
        assertThat(signed.send(notification, UUID.randomUUID()))
                .isInstanceOf(NotificationChannel.SendResult.Delivered.class);
        assertThat(ok.urls.getFirst()).contains("timestamp=").contains("sign=");

        RecordingTransport boom = new RecordingTransport(
                List.of(new WebhookTransport.Response(429, "30", "slow down")));
        WebhookChannel limited = channel(WebhookChannel.Platform.WECOM, boom);
        var result = (NotificationChannel.SendResult.RateLimited)
                limited.send(notification, UUID.randomUUID());
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    private static WebhookChannel channel(WebhookChannel.Platform platform,
                                          WebhookTransport transport) {
        return new WebhookChannel(platform, "https://webhook.test/hook", null, transport);
    }

    /** 假传输：预编排响应 + 记录请求 URL（断言加签） */
    private static final class RecordingTransport implements WebhookTransport {
        final List<String> urls = new ArrayList<>();
        private final java.util.Iterator<Response> responses;

        RecordingTransport(List<Response> responses) {
            this.responses = responses.iterator();
        }

        @Override
        public Response post(String url, String jsonBody) {
            urls.add(url);
            return responses.next();
        }
    }
}
