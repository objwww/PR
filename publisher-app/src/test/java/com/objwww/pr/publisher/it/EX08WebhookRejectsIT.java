package com.objwww.pr.publisher.it;

import com.objwww.pr.control.interfaces.webhook.WebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-08 签名错误/payload 畸形的 webhook：401/400，不入库不建 Run。
 * （事件过滤路径：非处理事件 200 ignored，同样零落库。）
 */
class EX08WebhookRejectsIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";

    private ItHarness harness;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
        controller = new WebhookController(SECRET, harness.intakeService);
    }

    @Test
    void badSignatureIs401() {
        byte[] body = ItHarness.webhookBody(3009L, "objwww/mall", 39, "head" + "8".repeat(36), "opened");
        var response = controller.handle(body, "sha256=" + "0".repeat(64), "pull_request", "ex08-d1");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertDbEmpty();
    }

    @Test
    void malformedPayloadIs400() {
        byte[] garbage = "this-is-not-json".getBytes(StandardCharsets.UTF_8);
        var response = controller.handle(garbage, ItHarness.sign(SECRET, garbage),
                "pull_request", "ex08-d2");
        assertThat(response.getStatusCode().value()).isEqualTo(400);

        // 合法 JSON 但缺必需字段（pull_request 对象）同样 400
        byte[] incomplete = "{\"action\":\"opened\",\"number\":1}".getBytes(StandardCharsets.UTF_8);
        var response2 = controller.handle(incomplete, ItHarness.sign(SECRET, incomplete),
                "pull_request", "ex08-d3");
        assertThat(response2.getStatusCode().value()).isEqualTo(400);
        assertDbEmpty();
    }

    @Test
    void unhandledEventsAre200Ignored() {
        byte[] labeled = ItHarness.webhookBody(3009L, "objwww/mall", 39,
                "head" + "8".repeat(36), "labeled");
        var r1 = controller.handle(labeled, ItHarness.sign(SECRET, labeled), "pull_request", "ex08-d4");
        assertThat(r1.getStatusCode().value()).isEqualTo(200);

        byte[] body = ItHarness.webhookBody(3009L, "objwww/mall", 39, "head" + "8".repeat(36), "opened");
        var r2 = controller.handle(body, ItHarness.sign(SECRET, body), "push", "ex08-d5");
        assertThat(r2.getStatusCode().value()).isEqualTo(200);
        assertDbEmpty();
    }

    private void assertDbEmpty() {
        assertThat(count("pr_subject")).isZero();
        assertThat(count("review_run")).isZero();
        assertThat(count("execution_event")).isZero();
        assertThat(count("artifact")).isZero();
    }
}
