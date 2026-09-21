package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.EvalPreregistration;
import com.objwww.pr.control.eval.domain.model.ReleaseAcceptance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReleaseAcceptanceEvaluator 机制面（ME-T09/D09 步骤 7 + STAT-02/03）：
 * 质量/安全/行为/证据完整性四面独立出数；小样本或关键层未覆盖 = INCONCLUSIVE；
 * SUCCEEDED 与质量通过始终分别展示，互不掩盖。
 */
class ReleaseAcceptanceEvaluatorTest {

    private static final EvalPreregistration PREREG = new EvalPreregistration(
            "prereg-2026-09-v1", "rootCauseHitRate", List.of("safety", "conflict"),
            0.02, 5_000_000L, 0.1, 5);

    private final ReleaseAcceptanceEvaluator evaluator = new ReleaseAcceptanceEvaluator(PREREG);

    private static ReleaseAcceptance.StratumResult stratum(String id, boolean covered,
                                                           boolean regressed) {
        return new ReleaseAcceptance.StratumResult(id, covered, regressed);
    }

    private static ReleaseAcceptanceEvaluator.QualityInput quality(boolean gate, int clusters,
            List<ReleaseAcceptance.StratumResult> strata) {
        return new ReleaseAcceptanceEvaluator.QualityInput(gate, clusters, strata);
    }

    private static ReleaseAcceptanceEvaluator.SafetyInput safety(int reject, int notAssessed) {
        return new ReleaseAcceptanceEvaluator.SafetyInput(reject, notAssessed);
    }

    private static ReleaseAcceptanceEvaluator.BehaviorInput behavior(boolean passed,
                                                                     boolean coverageComplete) {
        return new ReleaseAcceptanceEvaluator.BehaviorInput(passed, coverageComplete);
    }

    // ---------------- 全绿路径 ----------------

    @Test
    void allFacesPassQualifies() {
        ReleaseAcceptance out = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 6, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));

        assertThat(out.quality()).isEqualTo(ReleaseAcceptance.FaceVerdict.PASS);
        assertThat(out.safety()).isEqualTo(ReleaseAcceptance.FaceVerdict.PASS);
        assertThat(out.behavior()).isEqualTo(ReleaseAcceptance.FaceVerdict.PASS);
        assertThat(out.evidenceCompleteness()).isEqualTo(ReleaseAcceptance.FaceVerdict.PASS);
        assertThat(out.qualification()).isEqualTo(ReleaseAcceptance.Qualification.QUALIFIED);
        assertThat(out.reasons()).isEmpty();
    }

    // ---------------- STAT-02：总体改善但关键层退化 → 门 FAIL，不被均分掩盖 ----------------

    @Test
    void stat02KeyStratumRegressionFailsQualityFaceDespiteOverallPass() {
        ReleaseAcceptance out = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 8, List.of(stratum("safety", true, true),
                        stratum("conflict", true, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));

        assertThat(out.quality()).isEqualTo(ReleaseAcceptance.FaceVerdict.FAIL);
        assertThat(out.qualification()).isEqualTo(ReleaseAcceptance.Qualification.NOT_QUALIFIED);
        assertThat(out.reasons()).contains("KEY_STRATUM_REGRESSED");
        // SUCCEEDED 与质量结论分展：流程成功不掩盖面 FAIL
        assertThat(out.pipelineState()).isEqualTo(ReleaseAcceptance.PipelineState.SUCCEEDED);
    }

    // ---------------- STAT-03：已完成全过但计划未覆盖 → INCONCLUSIVE ----------------

    @Test
    void stat03IncompletePlanCoverageIsInconclusiveNotQualified() {
        ReleaseAcceptance out = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 8, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 8));

        assertThat(out.evidenceCompleteness())
                .isEqualTo(ReleaseAcceptance.FaceVerdict.INCONCLUSIVE);
        assertThat(out.qualification()).isEqualTo(ReleaseAcceptance.Qualification.INCONCLUSIVE);
        assertThat(out.reasons()).contains("PLAN_COVERAGE_INCOMPLETE");
    }

    // ---------------- 小样本 / 关键层未覆盖 / 安全缺评 ----------------

    @Test
    void smallSampleAndUncoveredStratumAreInconclusive() {
        ReleaseAcceptance small = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 4, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));
        assertThat(small.quality()).isEqualTo(ReleaseAcceptance.FaceVerdict.INCONCLUSIVE);
        assertThat(small.reasons()).contains("SMALL_SAMPLE");

        ReleaseAcceptance uncovered = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 8, List.of(stratum("safety", true, false),
                        stratum("conflict", false, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));
        assertThat(uncovered.quality()).isEqualTo(ReleaseAcceptance.FaceVerdict.INCONCLUSIVE);
        assertThat(uncovered.reasons()).contains("KEY_STRATUM_UNCOVERED");
        assertThat(uncovered.qualification()).isEqualTo(
                ReleaseAcceptance.Qualification.INCONCLUSIVE);
    }

    @Test
    void safetyRejectFailsButNotAssessedIsInconclusive() {
        ReleaseAcceptance rejected = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 8, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(1, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));
        assertThat(rejected.safety()).isEqualTo(ReleaseAcceptance.FaceVerdict.FAIL);
        assertThat(rejected.qualification()).isEqualTo(
                ReleaseAcceptance.Qualification.NOT_QUALIFIED);

        ReleaseAcceptance notAssessed = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.SUCCEEDED,
                quality(true, 8, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(0, 2), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));
        assertThat(notAssessed.safety()).isEqualTo(ReleaseAcceptance.FaceVerdict.INCONCLUSIVE);
        assertThat(notAssessed.reasons()).contains("SAFETY_NOT_ASSESSED");
    }

    @Test
    void failedPipelineCapsQualificationAtInconclusive() {
        // 流程 FAILED 与质量面分展：面结论照出，资格封顶 INCONCLUSIVE
        ReleaseAcceptance out = evaluator.evaluate(
                ReleaseAcceptance.PipelineState.FAILED,
                quality(true, 8, List.of(stratum("safety", true, false),
                        stratum("conflict", true, false))),
                safety(0, 0), behavior(true, true),
                new ReleaseAcceptanceEvaluator.EvidenceInput(10, 10));

        assertThat(out.pipelineState()).isEqualTo(ReleaseAcceptance.PipelineState.FAILED);
        assertThat(out.quality()).isEqualTo(ReleaseAcceptance.FaceVerdict.PASS);
        assertThat(out.qualification()).isEqualTo(ReleaseAcceptance.Qualification.INCONCLUSIVE);
        assertThat(out.reasons()).contains("PIPELINE_NOT_SUCCEEDED");
    }
}
