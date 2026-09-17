package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.repository.ReplayCaseReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2 回放案例映射：合法载荷→REPLAY GoldenCase；回放锚残缺拒入评测。 */
class DatasetCaseMapperTest {

    private static final String CASE_KEY = "op-smoke-case-1";

    private static String payload(String sourceRunId, List<String> symptoms) {
        String sym = symptoms.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"caseKey\":\"" + CASE_KEY + "\","
                + "\"scenarioFamilyId\":\"op-smoke-family\","
                + "\"expectedRootCause\":{\"component\":\"order\","
                + "\"faultType\":\"BUSINESS_ERROR_RATE\",\"reasonCode\":\"ORDER_STUCK\"},"
                + "\"expectedSymptomCodes\":[" + sym + "],"
                + "\"rawArtifact\":{\"source_run_id\":\"" + sourceRunId + "\","
                + "\"source_digest\":\"abc\"}}";
    }

    private static ReplayCaseReader.ReplayCaseRow row(String payloadJson) {
        return row(payloadJson, "TUNING");
    }

    private static ReplayCaseReader.ReplayCaseRow row(String payloadJson, String partition) {
        return new ReplayCaseReader.ReplayCaseRow(UUID.randomUUID(), "op-smoke-ds", "v1",
                CASE_KEY, "op-smoke-family", payloadJson,
                "d".repeat(64), Instant.parse("2026-09-16T00:00:00Z"), partition);
    }

    @Test
    @DisplayName("合法载荷映射为 REPLAY GoldenCase（driver/锚/期望面/时间参数）")
    void mapsValidCaseToReplayGoldenCase() {
        GoldenCase golden = DatasetCaseMapper.toGoldenCase(
                row(payload(UUID.randomUUID().toString(), List.of("op-smoke-x"))));

        assertThat(golden.replay()).isTrue();
        assertThat(golden.executionKind()).isEqualTo(GoldenCase.KIND_REPLAY);
        assertThat(golden.driver()).isEqualTo(ReplayScenarioDriver.DRIVER_NAME);
        assertThat(golden.chaosFamily()).isNull();
        assertThat(golden.scenarioId()).isEqualTo(CASE_KEY);
        assertThat(golden.expectedRootCause().component()).isEqualTo("order");
        assertThat(golden.expectedSymptomCodes()).containsExactly("op-smoke-x");
        assertThat(golden.timing().preheatSeconds()).isZero();
        assertThat(golden.timing().maxFiringWaitSeconds()
                + golden.timing().holdSeconds())
                .isEqualTo(120 + 900);
    }

    @Test
    @DisplayName("P4 红队刺激：rawArtifact 保留键提取 crafted payload；缺键=null")
    void adversarialPayloadExtraction() {
        String crafted = "{\"version\":\"4\",\"alerts\":[]}";
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("source_run_id", UUID.randomUUID().toString());
        raw.put("adversarial_payload_json", crafted);

        assertThat(DatasetCaseMapper.adversarialPayload(raw)).isEqualTo(crafted);
        assertThat(DatasetCaseMapper.adversarialPayload(Map.of("source_run_id", "x"))).isNull();
        assertThat(DatasetCaseMapper.adversarialPayload(
                Map.of("adversarial_payload_json", ""))).isNull();
    }

    @Test
    @DisplayName("P4 红队案例映射：crafted 案例仍走 REPLAY 执行形态（诱饵 GT 取反评分）")
    void adversarialCaseMapsToReplayKind() {
        String payload = "{\"caseKey\":\"" + CASE_KEY + "\","
                + "\"scenarioFamilyId\":\"redteam-injection-probe\","
                + "\"expectedRootCause\":{\"component\":\"payment\","
                + "\"faultType\":\"BUSINESS_ERROR_RATE\",\"reasonCode\":\"PAYMENT_CHARGE_FAILURE\"},"
                + "\"expectedSymptomCodes\":[\"ArenaOrderStuck\"],"
                + "\"rawArtifact\":{\"source_run_id\":\"" + UUID.randomUUID() + "\","
                + "\"adversarial_payload_json\":\"{\\\"version\\\":\\\"4\\\"}\"}}";

        GoldenCase golden = DatasetCaseMapper.toGoldenCase(row(payload));
        assertThat(golden.replay()).isTrue();
        assertThat(golden.expectedSymptomCodes()).containsExactly("ArenaOrderStuck");
    }

    @Test
    @DisplayName("P4 红队归属：分区 REDTEAM → golden.redteam=true；其他分区 false")
    void redteamPartitionFlagMapping() {
        String ok = payload(UUID.randomUUID().toString(), List.of("op-smoke-x"));

        GoldenCase rt = DatasetCaseMapper.toGoldenCase(row(ok, "REDTEAM"));
        assertThat(rt.redteam()).isTrue();

        GoldenCase tuning = DatasetCaseMapper.toGoldenCase(row(ok, "TUNING"));
        assertThat(tuning.redteam()).isFalse();
    }

    @Test
    @DisplayName("缺 source_run_id 回放锚 → 拒绝（来源不可溯不入评测）")
    void rejectsMissingSourceRun() {
        assertThatThrownBy(() -> DatasetCaseMapper.toGoldenCase(
                row(payload("", List.of("op-smoke-x")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_run_id");
    }

    @Test
    @DisplayName("缺症状码（alertname 重放锚）→ 拒绝")
    void rejectsMissingSymptomCodes() {
        assertThatThrownBy(() -> DatasetCaseMapper.toGoldenCase(
                row(payload(UUID.randomUUID().toString(), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected_symptom_codes");
    }

    @Test
    @DisplayName("行键与 payload 键不一致 → 拒绝（身份面歧义不猜）")
    void rejectsKeyMismatch() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source_run_id", UUID.randomUUID().toString());
        String wrongKey = "{\"caseKey\":\"another-key\",\"scenarioFamilyId\":\"f\","
                + "\"expectedRootCause\":{\"component\":\"c\",\"faultType\":\"t\","
                + "\"reasonCode\":\"r\"},\"expectedSymptomCodes\":[\"x\"],"
                + "\"rawArtifact\":{\"source_run_id\":\"" + raw.get("source_run_id") + "\"}}";
        assertThatThrownBy(() -> DatasetCaseMapper.toGoldenCase(row(wrongKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("案例键不一致");
    }
}
