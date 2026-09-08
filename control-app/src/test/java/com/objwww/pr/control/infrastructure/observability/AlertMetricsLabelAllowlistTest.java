package com.objwww.pr.control.infrastructure.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * M3-28：指标面 allowlist——tag 键只允许 decision/validation/engine/disagree/outcome，
 * 值只允许封闭枚举名；UUID/任意串进 tag 的高基数爆炸被结构性钉死。
 * M6-02 观察面成账：engine 维度（HOLMES/NATIVE 分桶）+ rca_engine_comparison_total。
 * M6-04：rca_fallback_decision_total（outcome = CastOutcome 封闭集）。
 */
class AlertMetricsLabelAllowlistTest {

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AlertMetrics metrics = new AlertMetrics(registry);

    @Test
    @DisplayName("计数按标签分桶正确；null/blank 归 unknown；负延迟不记录")
    void countersBucketByClosedEnumLabels() {
        metrics.taskDecision("COMPLETED", "HOLMES");
        metrics.taskDecision("COMPLETED", "HOLMES");
        metrics.taskDecision("DEAD", "NATIVE");
        metrics.attemptFinished("STRUCTURE_VALIDATED", "HOLMES");
        metrics.attemptFinished(null, "NATIVE");
        metrics.attemptFinished("  ", "HOLMES");
        metrics.attemptLatency(12, "HOLMES");
        metrics.attemptLatency(-1, "NATIVE");
        metrics.engineComparison(true);
        metrics.engineComparison(false);
        metrics.engineComparison(false);

        assertThat(registry.counter("rca_task_decision_total",
                "decision", "COMPLETED", "engine", "HOLMES").count()).isEqualTo(2.0);
        assertThat(registry.counter("rca_task_decision_total",
                "decision", "DEAD", "engine", "NATIVE").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_attempt_finished_total",
                "validation", "STRUCTURE_VALIDATED", "engine", "HOLMES").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("rca_attempt_finished_total",
                "validation", "unknown", "engine", "NATIVE").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_attempt_finished_total",
                "validation", "unknown", "engine", "HOLMES").count()).isEqualTo(1.0);
        assertThat(registry.get("rca_attempt_latency").timer().count()).isEqualTo(1);
        assertThat(registry.counter("rca_engine_comparison_total",
                "disagree", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_engine_comparison_total",
                "disagree", "false").count()).isEqualTo(2.0);
        metrics.fallbackDecision("CAST");
        metrics.fallbackDecision("ALREADY_CAST");
        metrics.fallbackDecision("ALREADY_CAST");
        assertThat(registry.counter("rca_fallback_decision_total",
                "outcome", "CAST").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_fallback_decision_total",
                "outcome", "ALREADY_CAST").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("allowlist：全 registry tag 键 ⊆ {decision, validation, engine, disagree}，值无 UUID 且截断 64")
    void labelKeysStayWithinAllowlist() {
        metrics.taskDecision("COMPLETED", "HOLMES");
        metrics.taskDecision("DEAD", "x".repeat(200));
        metrics.taskDecision("RETRY_SCHEDULED", null);
        metrics.attemptFinished("REJECTED_MALFORMED", "NATIVE");
        metrics.attemptLatency(5, "HOLMES");
        metrics.engineComparison(true);
        metrics.fallbackDecision("INELIGIBLE_ERROR_CLASS");

        List<String> allowlist = List.of("decision", "validation", "engine", "disagree",
                "outcome");
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(allowlist).contains(tag.getKey());
                assertThat(tag.getValue().length()).isLessThanOrEqualTo(64);
                assertThat(UUID_LIKE.matcher(tag.getValue()).matches()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("NOOP 实例独立可用（自带 throwaway registry），永不外泄")
    void noopInstanceIsUsable() {
        assertThatCode(() -> {
            AlertMetrics.NOOP.taskDecision("COMPLETED", "HOLMES");
            AlertMetrics.NOOP.attemptFinished("STRUCTURE_VALIDATED", "NATIVE");
            AlertMetrics.NOOP.attemptLatency(3, "HOLMES");
            AlertMetrics.NOOP.engineComparison(true);
            AlertMetrics.NOOP.fallbackDecision("CAST");
        }).doesNotThrowAnyException();
    }
}
