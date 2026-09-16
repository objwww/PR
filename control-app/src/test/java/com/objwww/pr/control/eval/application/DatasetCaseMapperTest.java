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
        return new ReplayCaseReader.ReplayCaseRow(UUID.randomUUID(), "op-smoke-ds", "v1",
                CASE_KEY, "op-smoke-family", payloadJson,
                "d".repeat(64), Instant.parse("2026-09-16T00:00:00Z"));
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
