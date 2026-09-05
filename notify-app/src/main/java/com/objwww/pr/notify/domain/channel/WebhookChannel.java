package com.objwww.pr.notify.domain.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * webhook 机器人渠道（钉钉/企微；TEST_CHANNEL 与 LIVE 共用实现，档位只是配置）。
 *
 * <p>钉钉加签（冻结算法）：sign = Base64(HmacSHA256(secret, timestamp+"\n"+secret))，
 * 追加 {@code &timestamp=..&sign=..}——secret 仅 env 注入（INV-AM3-3），不落日志/落库。
 * 文案由渲染器消毒，本类只包装平台 body；webhook URL 不入任何持久化面。
 * 触网一律经 {@link WebhookTransport} 端口（测试假件替换；分类判定在 Classifier）。
 */
public final class WebhookChannel implements NotificationChannel {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 平台形态（MVP 只两种；渠道扩展 P-34 留白） */
    public enum Platform {DINGTALK, WECOM}

    private final Platform platform;
    private final String webhookUrl;
    private final String signSecret; // 钉钉加签可空；企微不适用
    private final WebhookTransport transport;

    public WebhookChannel(Platform platform, String webhookUrl, String signSecret,
                          WebhookTransport transport) {
        this.platform = Objects.requireNonNull(platform);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "webhook 未注入（渠道 " + platform + " 拒绝无凭证创建，INV-AM3-3）");
        }
        this.webhookUrl = webhookUrl;
        this.signSecret = signSecret == null || signSecret.isBlank() ? null : signSecret;
        this.transport = Objects.requireNonNull(transport);
    }

    @Override
    public SendResult send(RenderedNotification notification, UUID operationId) {
        String url = signSecret == null ? webhookUrl
                : signed(webhookUrl, System.currentTimeMillis(), signSecret);
        try {
            WebhookTransport.Response response = transport.post(url,
                    requestBody(notification));
            return WebhookTransport.Classifier.fromStatus(response.status(),
                    response.retryAfterHeader());
        } catch (WebhookTransport.TransportFailure e) {
            return WebhookTransport.Classifier.fromTransport(e.getCause());
        }
    }

    /** 平台 body（包内可见，供假 transport 断言与测试） */
    String requestBody(RenderedNotification notification) {
        try {
            var body = JSON.createObjectNode();
            body.put("msgtype", "markdown");
            var markdown = body.putObject("markdown");
            if (platform == Platform.DINGTALK) {
                markdown.put("title", notification.title());
                markdown.put("text", notification.text());
            } else {
                markdown.put("content", notification.title() + "\n" + notification.text());
            }
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("通知 body 序列化失败", e);
        }
    }

    /**
     * 纯函数加签（离线向量测试锚点）：数据 = {@code timestamp+"\n"+secret}，
     * 密钥 = secret（钉钉官方签名规范）。
     */
    static String signed(String url, long timestampMillis, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((timestampMillis + "\n" + secret)
                    .getBytes(StandardCharsets.UTF_8));
            String encoded = java.net.URLEncoder.encode(
                    Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + "timestamp=" + timestampMillis + "&sign=" + encoded;
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败", e);
        }
    }
}
