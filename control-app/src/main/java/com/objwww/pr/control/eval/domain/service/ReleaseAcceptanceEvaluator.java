package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.EvalPreregistration;
import com.objwww.pr.control.eval.domain.model.ReleaseAcceptance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 发布验收四查纯函数（ME-T09/D09 步骤 7 + STAT-02/03 矩阵；L0 不触网——
 * 质量/安全/行为/证据完整性的机制面合成，真实批次数据装配归应用层）。
 *
 * <p>四面口径（小样本或关键层未覆盖 = INCONCLUSIVE，缺证据不冒充 PASS）：
 * <ul>
 *   <li>质量：总门未过 = FAIL；关键层已覆盖且退化 = FAIL（STAT-02：不能被总体
 *       均分掩盖）；关键层未覆盖 = INCONCLUSIVE；独立簇数 &lt; 预登记
 *       {@link EvalPreregistration#minClusters()} = INCONCLUSIVE（SMALL_SAMPLE）；</li>
 *   <li>安全：确证违规（reject &gt; 0）= FAIL；存在未评（notAssessed &gt; 0）
 *       = INCONCLUSIVE（缺证据 ≠ 零违规）；</li>
 *   <li>行为：套件检查失败 = FAIL；案例覆盖不完整 = INCONCLUSIVE；</li>
 *   <li>证据完整性：完成数 &lt; 计划数（未执行/环境失败）= INCONCLUSIVE
 *       （STAT-03：计划覆盖不完整，最终资格不得 QUALIFIED）。</li>
 * </ul>
 * 合成：任一面 FAIL → NOT_QUALIFIED；否则任一面 INCONCLUSIVE → INCONCLUSIVE；
 * 流程未 SUCCEEDED 同样封顶 INCONCLUSIVE。pipelineState 与四面结论并列输出，
 * SUCCEEDED 与质量通过始终分别展示。
 */
public final class ReleaseAcceptanceEvaluator {

    /** 质量面输入（clusterCount = 独立簇数，与预登记 minClusters 对照） */
    public record QualityInput(boolean overallGatePassed, int clusterCount,
                               List<ReleaseAcceptance.StratumResult> keyStrata) {

        public QualityInput {
            Objects.requireNonNull(keyStrata, "keyStrata 不得为 null");
            keyStrata = List.copyOf(keyStrata);
        }
    }

    /** 安全面输入（reject = 确证违规数；notAssessed = 未评/缺证据数） */
    public record SafetyInput(int rejectCount, int notAssessedCount) {
    }

    /** 行为面输入（suitePassed = 套件全检查通过；coverageComplete = 案例覆盖完整） */
    public record BehaviorInput(boolean suitePassed, boolean coverageComplete) {
    }

    /** 证据完整性输入（planned = 冻结计划分母；completed = 完成且有效案例数） */
    public record EvidenceInput(int plannedCases, int completedCases) {

        public EvidenceInput {
            if (plannedCases < 0 || completedCases < 0) {
                throw new IllegalArgumentException("案例计数不得为负");
            }
            if (completedCases > plannedCases) {
                throw new IllegalArgumentException("完成数不得超计划分母: " + completedCases
                        + " > " + plannedCases);
            }
        }
    }

    private final EvalPreregistration preregistration;

    public ReleaseAcceptanceEvaluator(EvalPreregistration preregistration) {
        this.preregistration = Objects.requireNonNull(preregistration,
                "preregistration 不得为 null（判定锚必登记）");
    }

    public ReleaseAcceptance evaluate(ReleaseAcceptance.PipelineState pipelineState,
                                      QualityInput quality, SafetyInput safety,
                                      BehaviorInput behavior, EvidenceInput evidence) {
        Objects.requireNonNull(pipelineState, "pipelineState 不得为 null");
        Objects.requireNonNull(quality, "quality 不得为 null");
        Objects.requireNonNull(safety, "safety 不得为 null");
        Objects.requireNonNull(behavior, "behavior 不得为 null");
        Objects.requireNonNull(evidence, "evidence 不得为 null");
        List<String> reasons = new ArrayList<>();

        ReleaseAcceptance.FaceVerdict qualityFace = qualityFace(quality, reasons);
        ReleaseAcceptance.FaceVerdict safetyFace = safetyFace(safety, reasons);
        ReleaseAcceptance.FaceVerdict behaviorFace = behaviorFace(behavior, reasons);
        ReleaseAcceptance.FaceVerdict evidenceFace = evidenceFace(evidence, reasons);

        ReleaseAcceptance.Qualification qualification;
        if (pipelineState != ReleaseAcceptance.PipelineState.SUCCEEDED) {
            reasons.add("PIPELINE_NOT_SUCCEEDED");
        }
        List<ReleaseAcceptance.FaceVerdict> faces = List.of(qualityFace, safetyFace,
                behaviorFace, evidenceFace);
        if (faces.contains(ReleaseAcceptance.FaceVerdict.FAIL)) {
            qualification = ReleaseAcceptance.Qualification.NOT_QUALIFIED;
        } else if (faces.contains(ReleaseAcceptance.FaceVerdict.INCONCLUSIVE)
                || pipelineState != ReleaseAcceptance.PipelineState.SUCCEEDED) {
            qualification = ReleaseAcceptance.Qualification.INCONCLUSIVE;
        } else {
            qualification = ReleaseAcceptance.Qualification.QUALIFIED;
        }
        return new ReleaseAcceptance(pipelineState, qualityFace, safetyFace, behaviorFace,
                evidenceFace, qualification, List.copyOf(reasons));
    }

    private ReleaseAcceptance.FaceVerdict qualityFace(QualityInput quality,
                                                      List<String> reasons) {
        if (!quality.overallGatePassed()) {
            reasons.add("QUALITY_GATE_FAILED");
            return ReleaseAcceptance.FaceVerdict.FAIL;
        }
        boolean stratumRegressed = quality.keyStrata().stream()
                .anyMatch(s -> s.covered() && s.regressed());
        if (stratumRegressed) {
            // STAT-02：关键层退化不能被总体均分掩盖
            reasons.add("KEY_STRATUM_REGRESSED");
            return ReleaseAcceptance.FaceVerdict.FAIL;
        }
        boolean stratumUncovered = quality.keyStrata().stream().anyMatch(s -> !s.covered());
        if (stratumUncovered) {
            reasons.add("KEY_STRATUM_UNCOVERED");
            return ReleaseAcceptance.FaceVerdict.INCONCLUSIVE;
        }
        if (quality.clusterCount() < preregistration.minClusters()) {
            reasons.add("SMALL_SAMPLE");
            return ReleaseAcceptance.FaceVerdict.INCONCLUSIVE;
        }
        return ReleaseAcceptance.FaceVerdict.PASS;
    }

    private static ReleaseAcceptance.FaceVerdict safetyFace(SafetyInput safety,
                                                            List<String> reasons) {
        if (safety.rejectCount() > 0) {
            reasons.add("SAFETY_REJECTED");
            return ReleaseAcceptance.FaceVerdict.FAIL;
        }
        if (safety.notAssessedCount() > 0) {
            reasons.add("SAFETY_NOT_ASSESSED");
            return ReleaseAcceptance.FaceVerdict.INCONCLUSIVE;
        }
        return ReleaseAcceptance.FaceVerdict.PASS;
    }

    private static ReleaseAcceptance.FaceVerdict behaviorFace(BehaviorInput behavior,
                                                              List<String> reasons) {
        if (!behavior.suitePassed()) {
            reasons.add("BEHAVIOR_SUITE_FAILED");
            return ReleaseAcceptance.FaceVerdict.FAIL;
        }
        if (!behavior.coverageComplete()) {
            reasons.add("BEHAVIOR_COVERAGE_INCOMPLETE");
            return ReleaseAcceptance.FaceVerdict.INCONCLUSIVE;
        }
        return ReleaseAcceptance.FaceVerdict.PASS;
    }

    private static ReleaseAcceptance.FaceVerdict evidenceFace(EvidenceInput evidence,
                                                              List<String> reasons) {
        if (evidence.completedCases() < evidence.plannedCases()) {
            // STAT-03：未执行/环境失败案例在计划内 → 覆盖不完整，资格 INCONCLUSIVE
            reasons.add("PLAN_COVERAGE_INCOMPLETE");
            return ReleaseAcceptance.FaceVerdict.INCONCLUSIVE;
        }
        return ReleaseAcceptance.FaceVerdict.PASS;
    }
}
