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
        // M6-05：holmes shadow 抽样/工作裁定入账（outcome = 封闭枚举）
        metrics.holmesShadowSample("ENQUEUED");
        metrics.holmesShadowSample("BUDGET_EXHAUSTED");
        metrics.holmesShadowWork("COMPARISON_LANDED");
        metrics.holmesShadowWork("COMPARISON_LANDED");
        metrics.holmesShadowWork("CALIBRATION_LANDED");
        metrics.holmesShadowWork("CAS_LOST");
        assertThat(registry.counter("rca_holmes_shadow_sample_total",
                "outcome", "ENQUEUED").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_holmes_shadow_sample_total",
                "outcome", "BUDGET_EXHAUSTED").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_holmes_shadow_work_total",
                "outcome", "COMPARISON_LANDED").count()).isEqualTo(2.0);
        assertThat(registry.counter("rca_holmes_shadow_work_total",
                "outcome", "CALIBRATION_LANDED").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_holmes_shadow_work_total",
                "outcome", "CAS_LOST").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("allowlist：全 registry tag 键 ⊆ {decision, validation, engine, disagree, outcome, channel}，值无 UUID 且截断 64")
    void labelKeysStayWithinAllowlist() {
        metrics.taskDecision("COMPLETED", "HOLMES");
        metrics.taskDecision("DEAD", "x".repeat(200));
        metrics.taskDecision("RETRY_SCHEDULED", null);
        metrics.attemptFinished("REJECTED_MALFORMED", "NATIVE");
        metrics.attemptLatency(5, "HOLMES");
        metrics.engineComparison(true);
        metrics.fallbackDecision("INELIGIBLE_ERROR_CLASS");
        metrics.holmesShadowSample("RATE_NOT_SELECTED");
        metrics.holmesShadowWork("INCIDENT_BUSY");
        // WC-5：对账观测面全部入账（channel/decision 封闭集；runId 不进标签）
        metrics.reconcileScan("active", true, 5);
        metrics.reconcileScan("cleanup", false, 7);
        metrics.reconcileDecision("NOOP");
        metrics.cancelToQuiesce(12);
        metrics.cancelToQuiesce(-1);
        metrics.lateCommitRejected();
        metrics.unknownAction(3);
        metrics.unknownAction(0);
        metrics.reconcileScanSucceeded(123L);
        metrics.reconcileScanObserved(50, 2);

        List<String> allowlist = List.of("decision", "validation", "engine", "disagree",
                "outcome", "channel");
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(allowlist).contains(tag.getKey());
                assertThat(tag.getValue().length()).isLessThanOrEqualTo(64);
                assertThat(UUID_LIKE.matcher(tag.getValue()).matches()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("WC-5 看门狗观测面：扫描时长/失败计数分通道，gauge 覆盖写且 -1 保持上一拍")
    void wc5ReconcileObservabilityMeters() {
        metrics.reconcileScan("active", true, 5);
        metrics.reconcileScan("active", false, 7);
        metrics.reconcileScan("cleanup", false, 9);
        assertThat(registry.get("rca_reconcile_scan_duration").timers()).hasSize(2);
        assertThat(registry.get("rca_reconcile_scan_duration").tags("channel", "active")
                .timer().count()).isEqualTo(2);
        assertThat(registry.counter("rca_reconcile_scan_failure_total",
                "channel", "active").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_reconcile_scan_failure_total",
                "channel", "cleanup").count()).isEqualTo(1.0);
        assertThat(registry.counter("rca_reconcile_scan_failure_total",
                "channel", "active").count()).isEqualTo(1.0);

        // gauge 族：成功拍覆盖写；读取失败拍传 -1 = 保持上一拍（不假造 0）
        metrics.reconcileScanSucceeded(1_000L);
        metrics.reconcileScanObserved(2_000L, 3);
        assertThat(registry.get("rca_reconcile_last_success_epoch_ms").gauge().value())
                .isEqualTo(1_000.0);
        assertThat(registry.get("rca_reconcile_oldest_unseen_age_ms").gauge().value())
                .isEqualTo(2_000.0);
        assertThat(registry.get("rca_reconcile_terminal_run_open_tasks").gauge().value())
                .isEqualTo(3.0);
        metrics.reconcileScanObserved(-1, -1);
        assertThat(registry.get("rca_reconcile_oldest_unseen_age_ms").gauge().value())
                .isEqualTo(2_000.0);
        assertThat(registry.get("rca_reconcile_terminal_run_open_tasks").gauge().value())
                .isEqualTo(3.0);

        // 计数族：decision/迟到提交/UNKNOWN 合并计数；负时长静默丢弃
        metrics.reconcileDecision("NOOP");
        metrics.reconcileDecision("NOOP");
        metrics.reconcileDecision("FORCE_CANCEL");
        assertThat(registry.counter("rca_reconcile_decision_total",
                "decision", "NOOP").count()).isEqualTo(2.0);
        assertThat(registry.counter("rca_reconcile_decision_total",
                "decision", "FORCE_CANCEL").count()).isEqualTo(1.0);
        metrics.lateCommitRejected();
        assertThat(registry.get("rca_late_commit_rejected_total").counter().count())
                .isEqualTo(1.0);
        metrics.unknownAction(4);
        metrics.unknownAction(0);
        assertThat(registry.get("rca_reconcile_unknown_action_total").counter().count())
                .isEqualTo(4.0);
        // 负时长不落表（timer 惰性注册：无记录 = 无 meter）
        assertThat((Object) registry.find("rca_cancel_to_quiesce").timer()).isNull();
        metrics.cancelToQuiesce(12);
        assertThat(registry.get("rca_cancel_to_quiesce").timer().count()).isEqualTo(1);
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
