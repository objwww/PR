package com.objwww.pr.publisher.it;

import com.objwww.pr.control.interfaces.webhook.WebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-05 webhook 重投（B-3）：同一 delivery 发两次 → run_key 唯一约束兜底，
 * 只有一个 Run；两次响应均 2xx。
 */
class ST05WebhookRedeliveryIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";
    private static final String HEAD_SHA = "head" + "f".repeat(36);

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void sameDeliveryTwiceCreatesSingleRun() {
        harness.sourcePort.registerSnapshot(HEAD_SHA, ItTarballs.singleFile("src/A.java", "class A {}\n"))
                .registerDiff(ItHarness.BASE_SHA, HEAD_SHA, "diff");
        WebhookController controller = new WebhookController(SECRET, harness.intakeService);
        byte[] body = ItHarness.webhookBody(2005L, "objwww/mall", 25, HEAD_SHA, "opened");
        String signature = ItHarness.sign(SECRET, body);

        ResponseEntity<?> first = controller.handle(body, signature, "pull_request", "st05-delivery");
        ResponseEntity<?> second = controller.handle(body, signature, "pull_request", "st05-delivery");

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(count("review_run")).isEqualTo(1);
        assertThat(count("pr_subject")).isEqualTo(1);
        assertThat(count("pr_revision")).isEqualTo(1);
        assertThat(harness.subjectRepo.findByRepositoryAndPrNumber(2005L, 25)
                .orElseThrow().getState().name()).isEqualTo("OPEN");
    }
}
