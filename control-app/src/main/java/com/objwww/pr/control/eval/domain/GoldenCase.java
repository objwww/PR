package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;

import java.util.List;
import java.util.Objects;

/**
 * GoldenCase（M3-10）：单场景的期望答案 + 执行时间参数——eval-scenarios.yml 预登记面的
 * 域对象，评分器（三指标 §6.4）与 ScenarioDriver（M3-17）的共同输入。
 *
 * <p>期望根因复用 {@link TypedRootCause}（与 EvidencePackage v2 的报告侧同构，
 * 评分即两侧的类型化等值 + 同义词归一）；期望症状码 = 期望 firing 的告警 alertname。
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
        Timing timing) {

    public GoldenCase {
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId 不得为 blank");
        }
        Objects.requireNonNull(expectedRootCause, "expectedRootCause");
        expectedSymptomCodes = expectedSymptomCodes == null
                ? List.of() : List.copyOf(expectedSymptomCodes);
        Objects.requireNonNull(timing, "timing");
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
