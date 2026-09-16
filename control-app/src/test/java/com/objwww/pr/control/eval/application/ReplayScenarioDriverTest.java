package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.repository.ReplayCaseReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2 回放驱动器：重投冻结载荷、resolved 变体纯函数、fail-closed 纪律。 */
class ReplayScenarioDriverTest {

    private static final String FIRING_PAYLOAD = """
            {"receiver":"oncall","status":"firing","alerts":[
              {"status":"firing","labels":{"alertname":"op-smoke-x","service":"order"},
               "annotations":{"summary":"s"},"startsAt":"2026-09-15T06:37:00Z",
               "endsAt":"0001-01-01T00:00:00Z","fingerprint":"fp-1"}]}
            """;

    private static GoldenCase replayCase() {
        return DatasetCaseMapper.toGoldenCase(new ReplayCaseReader.ReplayCaseRow(
                java.util.UUID.randomUUID(), "op-smoke-ds", "v1", "case-1", "fam",
                "{\"caseKey\":\"case-1\",\"scenarioFamilyId\":\"fam\","
                        + "\"expectedRootCause\":{\"component\":\"c\",\"faultType\":\"t\","
                        + "\"reasonCode\":\"r\"},\"expectedSymptomCodes\":[\"op-smoke-x\"],"
                        + "\"rawArtifact\":{\"source_run_id\":\""
                        + java.util.UUID.randomUUID() + "\"}}",
                "d".repeat(64), Instant.parse("2026-09-16T00:00:00Z")));
    }

    private static ReplayScenarioDriver driver(FrozenScript payloadScript,
            postedBodies posted, String bearer) {
        return new ReplayScenarioDriver(payloadScript, body -> {
            posted.bodies.add(new String(body, StandardCharsets.UTF_8));
            return "bad".equals(bearer) ? 401 : 202;
        }, () -> Instant.parse("2026-09-16T12:00:00Z"));
    }

    /** body 记录面（断言重投/清理两次载荷） */
    private static final class postedBodies {
        final List<String> bodies = new java.util.ArrayList<>();
    }

    private static final class FrozenScript
            implements ReplayScenarioDriver.FrozenPayloadReader {
        private Optional<FrozenPayload> next = Optional.empty();

        void offer(byte[] body) {
            next = Optional.of(new FrozenPayload(body,
                    com.objwww.pr.shared.Digest.sha256Of(
                            new String(body, StandardCharsets.UTF_8)).value()));
        }

        @Override
        public Optional<FrozenPayload> latestFiring(String alertname) {
            return next;
        }
    }

    @Test
    @DisplayName("activate 重投冻结原文（逐字节）并出回执；deactivate 重投 resolved 变体")
    void activateReplaysFrozenBytesAndDeactivatePostsResolvedVariant() throws Exception {
        FrozenScript payloads = new FrozenScript();
        payloads.offer(FIRING_PAYLOAD.getBytes(StandardCharsets.UTF_8));
        postedBodies posted = new postedBodies();
        ReplayScenarioDriver driver = driver(payloads, posted, "tok-1");

        ScenarioDriver.ActivationReceipt receipt =
                driver.activate(replayCase(), 1);

        assertThat(posted.bodies).hasSize(1);
        assertThat(posted.bodies.get(0)).isEqualTo(FIRING_PAYLOAD);
        assertThat(receipt.expectedAlertIdentity()).isEqualTo("op-smoke-x");
        assertThat(receipt.actionDigest()).startsWith("replay:").doesNotContain(" ");

        ScenarioDriver.RecoveryReceipt recovery =
                driver.deactivate(replayCase(), receipt);

        assertThat(posted.bodies).hasSize(2);
        assertThat(posted.bodies.get(1)).contains("\"status\":\"resolved\"");
        assertThat(posted.bodies.get(1)).contains("\"endsAt\":\"2026-09-16T12:00:00Z\"");
        assertThat(posted.bodies.get(1)).contains("\"alertname\":\"op-smoke-x\"");
        assertThat(posted.bodies.get(1)).doesNotContain("\"status\":\"firing\"");
        assertThat(recovery.criteriaMet()).isTrue();
        assertThat(recovery.alertsResolved()).isTrue();
        assertThat(recovery.unmetCriteria()).isEmpty();
    }

    @Test
    @DisplayName("无冻结载荷 → activate 拒绝（零伪造，不现编载荷）")
    void activateWithoutFrozenPayloadFailsClosed() {
        FrozenScript payloads = new FrozenScript();
        postedBodies posted = new postedBodies();
        ReplayScenarioDriver driver = driver(payloads, posted, "tok-1");

        assertThatThrownBy(() -> driver.activate(replayCase(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回放锚缺失");
        assertThat(posted.bodies).isEmpty();
    }

    @Test
    @DisplayName("bearer 未配置 → HttpWebhook fail-closed 拒发（INV-AM3-3 同律）")
    void blankBearerFailsClosed() throws Exception {
        ReplayScenarioDriver.HttpWebhook webhook =
                new ReplayScenarioDriver.HttpWebhook(
                        "http://control-app:8080/webhooks/alertmanager", "  ");

        assertThatThrownBy(() -> webhook.post(
                        FIRING_PAYLOAD.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bearer 未配置");
    }

    @Test
    @DisplayName("resolved 变体纯函数：status/endsAt 变更，labels/fingerprint 原样")
    void resolvedVariantIsDeterministicTransformation() {
        byte[] variant = ReplayScenarioDriver.buildResolvedVariant(
                FIRING_PAYLOAD.getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-17T08:30:00Z"));

        String text = new String(variant, StandardCharsets.UTF_8);
        assertThat(text).contains("\"status\":\"resolved\"")
                .contains("\"endsAt\":\"2026-09-17T08:30:00Z\"")
                .contains("\"fingerprint\":\"fp-1\"")
                .contains("\"service\":\"order\"");
        assertThat(text).doesNotContain("0001-01-01");
    }
}
