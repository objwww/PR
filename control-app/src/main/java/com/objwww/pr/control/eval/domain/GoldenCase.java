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
 *
 * <p>P2 执行形态（executionKind）：INJECT = 故障注入（既有驱动器，Prometheus 告警面）；
 * REPLAY = 冻结载荷重放（DatasetCaseMapper 从 case_version 产的回放案例——
 * OpenRCA/Meta point-in-time 形态：activate 重投 alert_inbox 冻结 firing 载荷，
 * 告警面 = DB incident 新 episode，跳过 Prometheus 探针）。null = INJECT（旧注册表兼容）。
 *
 * <p>P4 红队归属（redteam）：数据集分区为 REDTEAM 的案例为 true——诱饵 GT 取反
 * 评分（root_cause_hit=true = Agent 被劫持）与安全裁决行的红队归属共同输入。
 * YAML 注入场景恒 false。
 *
 * <p>P6-G8 难度分层（difficulty/panel）：RCA-Bench L1–L4 口径的难度标注
 * （L1=单故障单症状直因 … L4=复合/级联；null=未标注如实不出数）与 SMOKE panel
 * 快速回归子集归属（true=入选）。YAML 注册表 difficulty/panel 键与数据集案例
 * rawArtifact 保留键 gt_difficulty/gt_panel 双载体，mapper/registry 同律解析。
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
        Timing timing,
        String executionKind,
        List<String> expectedEvidenceCheckpoints,
        boolean redteam,
        String difficulty,
        boolean panel) {

    /** 难度词表（RCA-Bench L1–L4；registry/mapper 解析共用） */
    public static final List<String> DIFFICULTIES = List.of("L1", "L2", "L3", "L4");

    public static final String KIND_INJECT = "INJECT";
    public static final String KIND_REPLAY = "REPLAY";

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
        executionKind = executionKind == null || executionKind.isBlank()
                ? KIND_INJECT : executionKind;
        if (!KIND_INJECT.equals(executionKind) && !KIND_REPLAY.equals(executionKind)) {
            throw new IllegalArgumentException("非法执行形态: " + executionKind);
        }
        expectedEvidenceCheckpoints = expectedEvidenceCheckpoints == null
                ? List.of() : List.copyOf(expectedEvidenceCheckpoints);
        if (difficulty != null && !DIFFICULTIES.contains(difficulty)) {
            throw new IllegalArgumentException("非法难度标注: " + difficulty
                    + "（值域 L1–L4）");
        }
    }

    /** 兼容 P4 形态（13 参——难度/panel 未标注） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Map<String, String> expectedAlertLabels,
                      Injection injection, Timing timing, String executionKind,
                      List<String> expectedEvidenceCheckpoints, boolean redteam) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, expectedAlertLabels, injection, timing, executionKind,
                expectedEvidenceCheckpoints, redteam, null, false);
    }

    /** 兼容 M3-10 旧形态的便捷构造（无期望告警标签、无注入参数） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Timing timing) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, Map.of(), null, timing, KIND_INJECT, null);
    }

    /** 兼容 P3 形态（12 参——红队标志缺省 false） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Map<String, String> expectedAlertLabels,
                      Injection injection, Timing timing, String executionKind,
                      List<String> expectedEvidenceCheckpoints) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, expectedAlertLabels, injection, timing, executionKind,
                expectedEvidenceCheckpoints, false);
    }

    /** M3-17 形态（带期望告警标签与注入参数；执行形态 = INJECT） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Map<String, String> expectedAlertLabels,
                      Injection injection, Timing timing) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, expectedAlertLabels, injection, timing, KIND_INJECT,
                null);
    }

    /** P2 形态（带执行形态；无 GT 证据检查点） */
    public GoldenCase(String scenarioId, String name, String driver, String chaosFamily,
                      String target, TypedRootCause expectedRootCause,
                      List<String> expectedSymptomCodes, Map<String, String> expectedAlertLabels,
                      Injection injection, Timing timing, String executionKind) {
        this(scenarioId, name, driver, chaosFamily, target, expectedRootCause,
                expectedSymptomCodes, expectedAlertLabels, injection, timing, executionKind,
                null);
    }

    /** 回放形态判定（runner 分支与驱动器分派共用同一判据） */
    public boolean replay() {
        return KIND_REPLAY.equals(executionKind);
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
