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
 * M3-28：指标面 allowlist——tag 键只允许 decision/validation，值只允许封闭枚举名；
 * UUID/任意串进 tag 的高基数爆炸被结构性钉死。
 */
class AlertMetricsLabelAllowlistTest {

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AlertMetrics metrics = new AlertMetrics(registry);

    @Test
    @DisplayName("计数按标签分桶正确；null/blank 归 unknown；负延迟不记录")
    void countersBucketByClosedEnumLabels() {
        metrics.taskDecision("COMPLETED");
        metrics.taskDecision("COMPLETED");
        metrics.taskDecision("DEAD");
        metrics.attemptFinished("STRUCTURE_VALIDATED");
        metrics.attemptFinished(null);
        metrics.attemptFinished("  ");
        metrics.attemptLatency(12);
        metrics.attemptLatency(-1);

        assertThat(registry.counter("rca_task_decision_total", "decision", "COMPLETED").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("rca_task_decision_total", "decision", "DEAD").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("rca_attempt_finished_total", "validation", "STRUCTURE_VALIDATED").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("rca_attempt_finished_total", "validation", "unknown").count())
                .isEqualTo(2.0);
        assertThat(registry.get("rca_attempt_latency").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("allowlist：全 registry tag 键 ⊆ {decision, validation}，值无 UUID 且截断 64")
    void labelKeysStayWithinAllowlist() {
        metrics.taskDecision("COMPLETED");
        metrics.taskDecision("DEAD");
        metrics.taskDecision("x".repeat(200));
        metrics.attemptFinished("REJECTED_MALFORMED");
        metrics.attemptLatency(5);

        List<String> allowlist = List.of("decision", "validation");
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
            AlertMetrics.NOOP.taskDecision("COMPLETED");
            AlertMetrics.NOOP.attemptFinished("STRUCTURE_VALIDATED");
            AlertMetrics.NOOP.attemptLatency(3);
        }).doesNotThrowAnyException();
    }
}
