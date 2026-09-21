package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.EvidenceJudgeInput;
import com.objwww.pr.control.eval.domain.model.EvidenceSupportEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvidenceSupportEvaluator 机制面（ME-T09/D09 步骤 1 + JUDGE-01/03）：
 * 证据矛盾 FAIL 优先于 UNKNOWN；只有引用 ID 没有原文 → 支持性 NOT_ASSESSED
 * （UNKNOWN，不凭 ID 猜事实）；摘要断言不得超出证据支持面；任务约束未观测
 * 不猜通过。
 */
class EvidenceSupportEvaluatorTest {

    private final EvidenceSupportEvaluator evaluator = new EvidenceSupportEvaluator();

    private static EvidenceJudgeInput.EvidenceItem evidence(String id, String text) {
        return new EvidenceJudgeInput.EvidenceItem(id, text);
    }

    private static EvidenceJudgeInput.ClaimObservation claim(String id, String evidenceId,
            EvidenceJudgeInput.SupportRelation relation, boolean inSummary) {
        return new EvidenceJudgeInput.ClaimObservation(id, evidenceId, relation, inSummary);
    }

    private static Map<String, BehaviorEvaluation.Check> byName(EvidenceSupportEvaluation out) {
        return out.checks().stream().collect(Collectors.toMap(
                BehaviorEvaluation.Check::name, Function.identity()));
    }

    // ---------------- JUDGE-01：文笔完整但与证据矛盾 → 证据支持不得通过 ----------------

    @Test
    void judge01FluentReportContradictedByEvidenceFailsSupport() {
        // 报告四题面（judge-rubric-v2）评的是写作质量，本评估器评的是证据面——
        // 写作完整不自证证据支持：CONTRADICTS 断言 → evidence_support FAIL
        EvidenceJudgeInput in = new EvidenceJudgeInput("结构完整、六要素俱全的报告正文",
                List.of(evidence("ev-1", "连接池耗尽发生于 14:02，早于告警 14:05")),
                List.of(claim("c1", "ev-1", EvidenceJudgeInput.SupportRelation.CONTRADICTS,
                        true)),
                List.of(new EvidenceJudgeInput.ConstraintObservation("no-restart", true)));

        EvidenceSupportEvaluation out = evaluator.evaluate(in);

        BehaviorEvaluation.Check support = byName(out).get(
                EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT);
        assertThat(support.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(support.reasonCode()).isEqualTo("EVIDENCE_CONTRADICTS_CLAIM");
        assertThat(out.failureLabels()).contains("EVIDENCE_CONTRADICTS_CLAIM");
        // 摘要段断言被证据相反 → 摘要忠实同样 FAIL
        assertThat(byName(out).get(EvidenceSupportEvaluator.CHECK_SUMMARY_FIDELITY).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);
        // 任务约束面不受影响（逐面独立出数）
        assertThat(byName(out).get(
                EvidenceSupportEvaluator.CHECK_CONSTRAINT_COMPLIANCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(out.rubricVersion()).isEqualTo("evidence-rubric-v1");
    }

    // ---------------- JUDGE-03：没有原证据、只有引用 ID → 支持性 UNKNOWN ----------------

    @Test
    void judge03CitationIdWithoutTextIsUnknownNotGuessed() {
        EvidenceJudgeInput in = new EvidenceJudgeInput("引用了 ev-9 的报告",
                List.of(evidence("ev-9", null)),
                List.of(claim("c1", "ev-9", EvidenceJudgeInput.SupportRelation.SUPPORTS,
                        false)),
                List.of());

        EvidenceSupportEvaluation out = evaluator.evaluate(in);

        BehaviorEvaluation.Check support = byName(out).get(
                EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT);
        assertThat(support.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(support.reasonCode()).isEqualTo("EVIDENCE_TEXT_MISSING");
        assertThat(out.failureLabels()).isEmpty();
    }

    @Test
    void unresolvedCitationAndClaimWithoutEvidenceAreUnknown() {
        // 引用 ID 不在证据输入内 → CITATION_UNRESOLVED；断言无证据 → CLAIM_WITHOUT_EVIDENCE
        EvidenceJudgeInput unresolved = new EvidenceJudgeInput("报告",
                List.of(evidence("ev-1", "原文")),
                List.of(claim("c1", "ev-404", EvidenceJudgeInput.SupportRelation.SUPPORTS,
                        false)),
                List.of());
        assertThat(byName(evaluator.evaluate(unresolved)).get(
                EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT).reasonCode())
                .isEqualTo("CITATION_UNRESOLVED");

        EvidenceJudgeInput noEvidence = new EvidenceJudgeInput("报告",
                List.of(evidence("ev-1", "原文")),
                List.of(claim("c1", null, null, false)),
                List.of());
        BehaviorEvaluation.Check check = byName(evaluator.evaluate(noEvidence)).get(
                EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT);
        assertThat(check.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(check.reasonCode()).isEqualTo("CLAIM_WITHOUT_EVIDENCE");
    }

    @Test
    void contradictionOutranksUnknown() {
        // 一断言矛盾（确证 FAIL）+ 一断言缺原文（UNKNOWN）→ 面 FAIL，UNKNOWN 不稀释确证
        EvidenceJudgeInput in = new EvidenceJudgeInput("报告",
                List.of(evidence("ev-1", "原文一"), evidence("ev-2", null)),
                List.of(claim("c1", "ev-1", EvidenceJudgeInput.SupportRelation.CONTRADICTS,
                                false),
                        claim("c2", "ev-2", EvidenceJudgeInput.SupportRelation.SUPPORTS,
                                false)),
                List.of());

        EvidenceSupportEvaluation out = evaluator.evaluate(in);

        assertThat(byName(out).get(EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);
    }

    // ---------------- 支持成立 / 摘要忠实 / 任务约束 ----------------

    @Test
    void allSupportedClaimsPassWithMetricRatio() {
        EvidenceJudgeInput in = new EvidenceJudgeInput("报告",
                List.of(evidence("ev-1", "原文一"), evidence("ev-2", "原文二")),
                List.of(claim("c1", "ev-1", EvidenceJudgeInput.SupportRelation.SUPPORTS, true),
                        claim("c2", "ev-2", EvidenceJudgeInput.SupportRelation.SUPPORTS,
                                false)),
                List.of(new EvidenceJudgeInput.ConstraintObservation("budget", true)));

        EvidenceSupportEvaluation out = evaluator.evaluate(in);

        Map<String, BehaviorEvaluation.Check> checks = byName(out);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_SUMMARY_FIDELITY).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_CONSTRAINT_COMPLIANCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        BehaviorEvaluation.Metric metric = out.metrics().stream()
                .filter(m -> m.name().equals("claims_supported")).findFirst().orElseThrow();
        assertThat(metric.numerator()).isEqualTo(2);
        assertThat(metric.denominator()).isEqualTo(2);
        assertThat(out.failureLabels()).isEmpty();
    }

    @Test
    void summaryClaimBeyondEvidenceFailsFidelity() {
        // 正文断言有支持，但摘要段断言引用无关证据 → 摘要忠实 FAIL
        EvidenceJudgeInput in = new EvidenceJudgeInput("报告",
                List.of(evidence("ev-1", "原文一"), evidence("ev-2", "原文二")),
                List.of(claim("c1", "ev-1", EvidenceJudgeInput.SupportRelation.SUPPORTS, false),
                        claim("c2", "ev-2", EvidenceJudgeInput.SupportRelation.UNRELATED,
                                true)),
                List.of());

        EvidenceSupportEvaluation out = evaluator.evaluate(in);

        assertThat(byName(out).get(EvidenceSupportEvaluator.CHECK_SUMMARY_FIDELITY).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(out.failureLabels()).contains("SUMMARY_CLAIM_UNSUPPORTED",
                "EVIDENCE_DOES_NOT_SUPPORT");
    }

    @Test
    void constraintViolatedFailsUnobservedIsUnknown() {
        EvidenceJudgeInput violated = new EvidenceJudgeInput("报告", List.of(), List.of(),
                List.of(new EvidenceJudgeInput.ConstraintObservation("no-live", false)));
        assertThat(byName(evaluator.evaluate(violated)).get(
                EvidenceSupportEvaluator.CHECK_CONSTRAINT_COMPLIANCE).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);

        EvidenceJudgeInput unobserved = new EvidenceJudgeInput("报告", List.of(), List.of(),
                List.of(new EvidenceJudgeInput.ConstraintObservation("no-live", null)));
        BehaviorEvaluation.Check check = byName(evaluator.evaluate(unobserved)).get(
                EvidenceSupportEvaluator.CHECK_CONSTRAINT_COMPLIANCE);
        assertThat(check.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(check.reasonCode()).isEqualTo("CONSTRAINT_UNOBSERVED");
    }

    @Test
    void emptyPartitionsAreNotApplicable() {
        EvidenceSupportEvaluation out = evaluator.evaluate(
                new EvidenceJudgeInput("报告", List.of(), List.of(), List.of()));

        Map<String, BehaviorEvaluation.Check> checks = byName(out);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_EVIDENCE_SUPPORT).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_SUMMARY_FIDELITY).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(checks.get(EvidenceSupportEvaluator.CHECK_CONSTRAINT_COMPLIANCE).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
    }

    // ---------------- 输入契约防呆 ----------------

    @Test
    void rejectsRelationWithoutEvidence() {
        assertThatThrownBy(() -> new EvidenceJudgeInput.ClaimObservation("c1", null,
                EvidenceJudgeInput.SupportRelation.SUPPORTS, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceJudgeInput.ClaimObservation("c1", "ev-1",
                null, false))
                .isInstanceOf(NullPointerException.class);
    }
}
