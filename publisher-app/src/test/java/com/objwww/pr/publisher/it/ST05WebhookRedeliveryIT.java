package com.objwww.pr.publisher.it;

import com.objwww.pr.control.interfaces.webhook.WebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-05 webhook 重投（M1 两段式契约）：controller 写 inbox 幂等——同一 delivery 两次 POST
 * 均 202，inbox 仍一行；InboxProcessor 经 stub 权威读路由 FullReview → runIntakeDirect，
 * 全程一个 Run。防什么：重投若穿透幂等建第二个 Run，E2E-08 幂等保证即被破坏。
 */
class ST05WebhookRedeliveryIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";
    private static final String HEAD_SHA = "head" + "5".repeat(36);

    private ItHarness harness;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
        controller = new WebhookController(SECRET, harness.inboxRepo);
    }

    @Test
    void duplicateDeliveryAcceptsButCreatesSingleRun() {
        byte[] body = ItHarness.webhookBody(3005L, "objwww/mall", 55, HEAD_SHA, "opened");
        var first = controller.handle(body, ItHarness.sign(SECRET, body), "pull_request", "st05-d1");
        var second = controller.handle(body, ItHarness.sign(SECRET, body), "pull_request", "st05-d1");
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(202); // 重投仍 2xx（幂等防重）
        assertThat(count("webhook_inbox")).isEqualTo(1); // inbox 仅一行

        // T0 源内容注册（dispatch 同步跑 T0）；权威读 stub：open 非 draft，head 与事件一致
        harness.sourcePort.registerSnapshot(HEAD_SHA, ItTarballs.singleFile("src/A.java", "class A {}"))
                .registerDiff(ItHarness.BASE_SHA, HEAD_SHA, "diff --git a/src/A.java b/src/A.java");
        StubPrMetadataPort metadata = new StubPrMetadataPort()
                .remote("open", false, false, HEAD_SHA, ItHarness.BASE_SHA,
                        Instant.parse("2026-05-08T10:00:00Z"));

        harness.newInboxProcessor(metadata).runOnce();

        assertThat(count("review_run")).isEqualTo(1); // 重投经 inbox 路由后全程一个 Run
        // head_sha 在不可变 pr_revision 上（INC-27 同族：初版误查 review_run.head_sha，列不存在）
        String headInDb = adminJdbc.sql(
                        "SELECT r.head_sha FROM pr_revision r"
                                + " JOIN review_run rr ON rr.pr_revision_id = r.id")
                .query(String.class).single();
        assertThat(headInDb).isEqualTo(HEAD_SHA);
    }
}
