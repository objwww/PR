package com.objwww.pr.notify.domain.channel;

import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20 缺陷再现锚（评审 AM7 契约 2 原件）：原名 http200WithBusinessFailureIsStillMarkedDelivered——
 * 断言通过即缺陷在场的再现测试；EX-C2a 落地 F20 后缺陷移除，本锚翻转为钉住修复后语义：
 * HTTP 200 + errcode≠0 不得标 Delivered（→Retryable 进持久重试，last_error 记业务码）。
 */
class NotificationAuditCharacterizationTest {
    @Test
    void http200WithBusinessFailureIsNeverMarkedDelivered() {
        WebhookTransport transport = (url, json) ->
                new WebhookTransport.Response(200, null, "{\"errcode\":40014,\"errmsg\":\"rejected\"}");
        var channel = new WebhookChannel(WebhookChannel.Platform.WECOM,
                "https://audit.invalid/never-called", null, transport);
        var id = UUID.randomUUID();
        var result = (NotificationChannel.SendResult.Retryable)
                channel.send(new RenderedNotification("audit", "audit", id), id);
        assertThat(result.error()).contains("business_code_40014").contains("rejected");
    }
}
