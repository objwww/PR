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
 * {@code validation}（ValidationStatus 封闭集）。新指标加标签必须先扩本类并过
 * {@code AlertMetricsLabelAllowlistTest}——防"顺手把 UUID 放进 tag"的高基数爆炸。
 */
public final class AlertMetrics {

    /** 测试/无注册表面（独立 throwaway registry，永不暴露） */
    public static final AlertMetrics NOOP = new AlertMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;

    public AlertMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public void taskDecision(String decision) {
        registry.counter("rca_task_decision_total", "decision", safe(decision)).increment();
    }

    public void attemptFinished(String validationStatus) {
        registry.counter("rca_attempt_finished_total", "validation", safe(validationStatus)).increment();
    }

    public void attemptLatency(long millis) {
        if (millis >= 0) {
            registry.timer("rca_attempt_latency").record(Duration.ofMillis(millis));
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
