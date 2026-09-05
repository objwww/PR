package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GoldenCase（M3-10）：单场景的期望答案 + 执行时间参数——eval-scenarios.yml 预登记面的
 * 域对象，评分器（三指标 §6.4）与 ScenarioDriver（M3-17）的共同输入。
 *
 * <p>期望根因复用 {@link TypedRootCause}（与 EvidencePackage v2 的报告侧同构，
 * 评分即两侧的类型化等值 + 同义词归一）；期望症状码 = 期望 firing 的告警 alertname；
 * 期望告警标签 = 首条 expected_alerts 的 labels_frozen（C-6 指纹输入面，M3-17
 * ArenaChaosScenarioDriver 激活请求的 alertLabels——必含 alertname）。
 * 本类不做评分、不做注入，只承载期望面。
 */
public record GoldenCase(
        String scenarioId,
        String name,
        String driver,
        String chaosFamily,
        String target,
        TypedRootCause expectedRootCause,
        List<String> expectedSymptomCodes,
        Map<String, String> expectedAlertLabels,
        Injection injection,
        Timing timing) {

    public GoldenCase {
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId 不得为 blank");
        }
        Objects.requireNonNull(expectedRootCause, "expectedRootCause");
        expectedSymptomCodes = expectedSymptomCodes == null
                ? List.of() : List.copyOf(expectedSymptomCodes);
        expectedAlertLabels = expectedAlertLabels == null
                ? Map.of() : Map.copyOf(expectedAlertLabels);
        Objects.requireNonNull(timing, "timing");
    }

    /** 兼容 M3-10 旧形态的便捷构造（无期望告警标签、无注入参数） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Timing timing) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, Map.of(), null, timing);
    }

    /** 注入参数（注册表 injection 块；S1/S2 flag 面，靶场场景为 null） */
    public record Injection(String flag, String variant, String baselineVariant) {

        public Injection {
            Objects.requireNonNull(flag, "injection.flag");
            Objects.requireNonNull(variant, "injection.variant");
            Objects.requireNonNull(baselineVariant, "injection.baseline_variant");
        }
    }

    /** 评测时间参数显式化（M3-17：预热/持续/等待/清理不硬编码在 driver 里） */
    public record Timing(int preheatSeconds,
                         int holdSeconds,
                         int maxFiringWaitSeconds,
                         int maxResolvedWaitSeconds,
                         int cleanupTimeoutSeconds) {

        public Timing {
            if (preheatSeconds < 0 || holdSeconds < 0 || maxFiringWaitSeconds < 0
                    || maxResolvedWaitSeconds < 0 || cleanupTimeoutSeconds < 0) {
                throw new IllegalArgumentException("时间参数不得为负");
            }
        }
    }
}
