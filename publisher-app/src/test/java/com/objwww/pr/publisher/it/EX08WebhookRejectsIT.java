package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.interfaces.webhook.WebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-08 签名错误/payload 畸形的 webhook（M1-T03 两段式后的新契约）：
 * 401 不落库不动；畸形 JSON / 缺必需字段 / 非处理事件的裁决全部后移 InboxProcessor——
 * 入口一律 202 + inbox 留痕（E2E-22/ST-16，INC-16 关闭），Processor 再判
 * DEAD_LETTER(malformed) 或 IGNORED；全程零 Run 零业务表写入。
 *
 * <p>行为变更（相对 M0 原版 EX-08）：畸形 JSON 400 → 202 + raw 落库审计（E2E-22 新裁决）；
 * 非处理事件 200 ignored 不落库 → 202 + RECEIVED 行。
 */
class EX08WebhookRejectsIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";

    private ItHarness harness;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
        controller = new WebhookController(SECRET, harness.inboxRepo);
    }

    @Test
    void badSignatureIs401AndNothingPersisted() {
        byte[] body = ItHarness.webhookBody(3009L, "objwww/mall", 39, "head" + "8".repeat(36), "opened");
        var response = controller.handle(body, "sha256=" + "0".repeat(64), "pull_request", "ex08-d1");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(count("webhook_inbox")).isZero(); // 401 不落库（EX-08 不动）
        assertNoBusinessRows();
    }

    @Test
    void malformedJsonAcceptedThenDeadLetteredByProcessor() {
        // E2E-22：合法签名 + 畸形 JSON → 202 受理；payload_json NULL 落库审计；
        // Processor 判 DEAD_LETTER(malformed_json)，不建 Run 不重试
        byte[] garbage = "this-is-not-json".getBytes(StandardCharsets.UTF_8);
        var response = controller.handle(garbage, ItHarness.sign(SECRET, garbage),
                "pull_request", "ex08-d2");
        assertThat(response.getStatusCode().value()).isEqualTo(202);

        harness.newInboxProcessor(new StubPrMetadataPort()).runOnce(); // 权威读永不可达（解析即死信）

        assertThat(harness.inboxRepo.findByDeliveryId("ex08-d2").orElseThrow().getState())
                .isEqualTo(InboxState.DEAD_LETTER);
        assertNoBusinessRows();

        // 合法 JSON 但缺必需字段（pull_request 对象）→ 同裁决（malformed_payload）
        byte[] incomplete = "{\"action\":\"opened\",\"number\":1}".getBytes(StandardCharsets.UTF_8);
        var response2 = controller.handle(incomplete, ItHarness.sign(SECRET, incomplete),
                "pull_request", "ex08-d3");
        assertThat(response2.getStatusCode().value()).isEqualTo(202);

        harness.newInboxProcessor(new StubPrMetadataPort()).runOnce();

        assertThat(harness.inboxRepo.findByDeliveryId("ex08-d3").orElseThrow().getState())
                .isEqualTo(InboxState.DEAD_LETTER);
        assertNoBusinessRows();
    }

    @Test
    void unhandledEventsAcceptedThenIgnoredByProcessor() {
        // ST-16：六外 action / 非 pull_request 事件 → 202 + RECEIVED 行 → Processor IGNORED 留痕
        byte[] labeled = ItHarness.webhookBody(3009L, "objwww/mall", 39,
                "head" + "8".repeat(36), "labeled");
        var r1 = controller.handle(labeled, ItHarness.sign(SECRET, labeled), "pull_request", "ex08-d4");
        assertThat(r1.getStatusCode().value()).isEqualTo(202);

        byte[] body = ItHarness.webhookBody(3009L, "objwww/mall", 39, "head" + "8".repeat(36), "opened");
        var r2 = controller.handle(body, ItHarness.sign(SECRET, body), "push", "ex08-d5");
        assertThat(r2.getStatusCode().value()).isEqualTo(202);

        harness.newInboxProcessor(new StubPrMetadataPort()).runOnce();

        assertThat(harness.inboxRepo.findByDeliveryId("ex08-d4").orElseThrow().getState())
                .isEqualTo(InboxState.IGNORED);
        assertThat(harness.inboxRepo.findByDeliveryId("ex08-d5").orElseThrow().getState())
                .isEqualTo(InboxState.IGNORED);
        assertNoBusinessRows();
    }

    /** 业务表全零（webhook_inbox 是审计留痕，不在此列） */
    private void assertNoBusinessRows() {
        assertThat(count("pr_subject")).isZero();
        assertThat(count("review_run")).isZero();
        assertThat(count("execution_event")).isZero();
        assertThat(count("artifact")).isZero();
    }
}
