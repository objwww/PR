package com.objwww.pr.control.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.Objects;

/**
 * Micrometer 最小指标（M3-28；禁高基数 ID label——incident/run/task/attempt 一律
 * 不做 tag，只允许封闭枚举值标签）。
 *
 * <p>label allowlist：{@code decision}（FinishOutcome 封闭集）、
 * {@code validation}（ValidationStatus 封闭集）、{@code engine}
 * （RcaEngine 封闭集 + unknown）、{@code disagree}（引擎对照是否含差异）。
 * 新指标加标签必须先扩本类并过 {@code AlertMetricsLabelAllowlistTest}——防
 * "顺手把 UUID 放进 tag"的高基数爆炸。engine 维度（M6-02 观察面成账）：
 * 双引擎同指标分桶，对照期 dashboards 可直接并排看。
 */
public final class AlertMetrics {

    /** 测试/无注册表面（独立 throwaway registry，永不暴露） */
    public static final AlertMetrics NOOP = new AlertMetrics(new SimpleMeterRegistry());

    /** engine 标签缺省值（路由面缺失的历史 run 等） */
    public static final String ENGINE_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public AlertMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public void taskDecision(String decision, String engine) {
        registry.counter("rca_task_decision_total",
                "decision", safe(decision),
                "engine", safe(engine)).increment();
    }

    public void attemptFinished(String validationStatus, String engine) {
        registry.counter("rca_attempt_finished_total",
                "validation", safe(validationStatus),
                "engine", safe(engine)).increment();
    }

    public void attemptLatency(long millis, String engine) {
        if (millis >= 0) {
            registry.timer("rca_attempt_latency", "engine", safe(engine))
                    .record(Duration.ofMillis(millis));
        }
    }

    /** 引擎对照落账（M6-02）：disagree=true 含差异标记 / false 双侧一致 */
    public void engineComparison(boolean hasDisagreement) {
        registry.counter("rca_engine_comparison_total",
                "disagree", Boolean.toString(hasDisagreement)).increment();
    }

    /** run 级 fallback 裁定（M6-04）：outcome = FallbackService.CastOutcome 封闭集 */
    public void fallbackDecision(String outcome) {
        registry.counter("rca_fallback_decision_total",
                "outcome", safe(outcome)).increment();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
