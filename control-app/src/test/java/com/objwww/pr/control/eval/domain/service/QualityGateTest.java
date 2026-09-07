package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.ScenarioEvaluator;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.model.DimensionCounts;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.model.EvaluationRecordV1;
import com.objwww.pr.control.eval.domain.model.GateThresholds;
import com.objwww.pr.control.eval.domain.model.SafetyFace;
import com.objwww.pr.control.eval.domain.model.SixDimResult;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats.StatsResult;
import com.objwww.pr.control.eval.domain.service.QualityGate.GateDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5-08 质量门五分支冻结逻辑 UT（落码方案 §M5-08③④，v1.1 评审裁定逐字执行）：
 * <ol>
 *   <li>安全违规&gt;0 → REJECT；</li>
 *   <li>独立 cluster/关键分层数量不足 → INCONCLUSIVE；</li>
 *   <li>任一关键维配对差值 CI 下界 &lt; -margin → REJECT；</li>
 *   <li>运行门超预算/延迟/错误率 → REJECT；</li>
 *   <li>全部通过 → ELIGIBLE_FOR_CANARY。</li>
 * </ol>
 * 分支序即裁定序（1 优先于 2 有锁定案）；非 ELIGIBLE 结论必须带机器码未过原因
 * （门禁解释完整性，§12.1 L1）；阈值版本化（version 空即拒绝）。
 */
class QualityGateTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private final QualityGate gate = new QualityGate();

    // ------------------------------------------------------------ fixture 面

    private static GateThresholds thresholds() {
        return new GateThresholds("gate-thresholds-v1", 0.05, 5,
                60_000L, 100_000L, 0.10);
    }

    /** CONCLUSIVE 且区间下界 = +0.05（健康配对差值） */
    private static StatsResult healthyStats() {
        return new StatsResult(0.25, 0.05, 0.45, 8, 42L,
                PairedTrialStats.ALGORITHM_VERSION, PairedTrialStats.DEFAULT_RESAMPLES,
                PairedTrialStats.CI_METHOD, PairedTrialStats.Verdict.CONCLUSIVE);
    }

    private static SafetyGate.SafetyVerdict safetyPass() {
        return new SafetyGate.SafetyVerdict(List.of(), SafetyGate.Verdict.PASS);
    }

    private static SafetyGate.SafetyVerdict safetyReject() {
        return new SafetyGate.SafetyVerdict(List.of(new SafetyGate.Violation(
                SafetyFace.UNAUTHORIZED_TOOL, "tool_call:1", "UNKNOWN_TOOL")),
                SafetyGate.Verdict.REJECT);
    }

    /** 六维 run 聚合面：成本/过程全在阈值内（latency 100、tokens 150、零错误） */
    private static SixDimResult cleanRun() {
        return new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(1, 0, 0, 1,
                        true, false, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Process(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(100L, 100L, 50L, 150L, false),
                        List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(1, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(0, false), List.of()));
    }

    // ---------------------------------------------------------------- 用例面

    @Test
    @DisplayName("分支1：安全违规>0 → REJECT（即使配对统计健康也不得放行）")
    void branch1SafetyViolationsReject() {
        GateDecision d = gate.evaluate(safetyReject(), healthyStats(), cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_SAFETY_VIOLATIONS);
    }

    @Test
    @DisplayName("分支序锁定：安全违规 + cluster 不足并存 → REJECT（分支1 先于分支2）")
    void branch1BeatsBranch2FrozenOrder() {
        StatsResult shortage = new StatsResult(0.25, null, null, 3, 42L,
                PairedTrialStats.ALGORITHM_VERSION, PairedTrialStats.DEFAULT_RESAMPLES,
                PairedTrialStats.CI_METHOD, PairedTrialStats.Verdict.INCONCLUSIVE);
        GateDecision d = gate.evaluate(safetyReject(), shortage, cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_SAFETY_VIOLATIONS);
    }

    @Test
    @DisplayName("分支2a：无配对试验（stats 缺失）→ INCONCLUSIVE（小样本不自动放行）")
    void branch2aMissingStatsInconclusive() {
        GateDecision d = gate.evaluate(safetyPass(), null, cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.INCONCLUSIVE);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_INSUFFICIENT_CLUSTERS);
    }

    @Test
    @DisplayName("分支2b：统计结论 INCONCLUSIVE → INCONCLUSIVE")
    void branch2bInconclusiveVerdict() {
        StatsResult shortage = new StatsResult(0.25, null, null, 3, 42L,
                PairedTrialStats.ALGORITHM_VERSION, PairedTrialStats.DEFAULT_RESAMPLES,
                PairedTrialStats.CI_METHOD, PairedTrialStats.Verdict.INCONCLUSIVE);
        GateDecision d = gate.evaluate(safetyPass(), shortage, cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.INCONCLUSIVE);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_INSUFFICIENT_CLUSTERS);
    }

    @Test
    @DisplayName("分支2c：cluster 数 < 阈值下限（8 < 10）→ INCONCLUSIVE")
    void branch2cClusterShortageAgainstThreshold() {
        GateThresholds strict = new GateThresholds("gate-thresholds-v1", 0.05, 10,
                60_000L, 100_000L, 0.10);
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), cleanRun(), strict);
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.INCONCLUSIVE);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_INSUFFICIENT_CLUSTERS);
    }

    @Test
    @DisplayName("分支3：CI 下界 < -margin（-0.08 < -0.05）→ REJECT")
    void branch3CiLowerBelowMarginReject() {
        StatsResult regressive = new StatsResult(-0.02, -0.08, 0.04, 8, 42L,
                PairedTrialStats.ALGORITHM_VERSION, PairedTrialStats.DEFAULT_RESAMPLES,
                PairedTrialStats.CI_METHOD, PairedTrialStats.Verdict.CONCLUSIVE);
        GateDecision d = gate.evaluate(safetyPass(), regressive, cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_CI_LOWER_BELOW_MARGIN);
    }

    @Test
    @DisplayName("分支3 边界：CI 下界 = -margin 恰相等 → 不触发（严格小于才拒）")
    void branch3BoundaryExactMarginPasses() {
        StatsResult boundary = new StatsResult(0.0, -0.05, 0.05, 8, 42L,
                PairedTrialStats.ALGORITHM_VERSION, PairedTrialStats.DEFAULT_RESAMPLES,
                PairedTrialStats.CI_METHOD, PairedTrialStats.Verdict.CONCLUSIVE);
        GateDecision d = gate.evaluate(safetyPass(), boundary, cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY);
    }

    @Test
    @DisplayName("分支4a：运行门延迟超限 → REJECT")
    void branch4aLatencyOverRunGate() {
        SixDimResult slow = new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(1, 0, 0, 1,
                        true, false, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Process(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(70_000L, 100L, 50L, 150L, false),
                        List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(1, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(0, false), List.of()));
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), slow, thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_RUN_LATENCY_EXCEEDED);
    }

    @Test
    @DisplayName("分支4b：运行门 token 预算超限 → REJECT")
    void branch4bTokenBudgetOverRunGate() {
        SixDimResult costly = new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(1, 0, 0, 1,
                        true, false, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Process(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(100L, 90_000L, 50_000L,
                        140_000L, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(1, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(0, false), List.of()));
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), costly, thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_RUN_TOKEN_BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("分支4c：运行门工具错误率超限（1/2=0.5 > 0.10）→ REJECT；零调用错误率记 0 不触发")
    void branch4cToolErrorRateOverRunGate() {
        SixDimResult errorProne = new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(1, 0, 0, 1,
                        true, false, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Process(2, 0, 1), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(2, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(100L, 100L, 50L, 150L, false),
                        List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(1, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(0, false), List.of()));
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), errorProne, thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(d.reasons()).containsExactly(QualityGate.REASON_RUN_ERROR_RATE_EXCEEDED);

        SixDimResult noCalls = new SixDimResult(
                new SixDimResult.Dim<>(new DimensionCounts.Result(1, 0, 0, 1,
                        true, false, false), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Process(0, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Tool(0, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Cost(100L, 100L, 50L, 150L, false),
                        List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Collaboration(1, 0, 0), List.of()),
                new SixDimResult.Dim<>(new DimensionCounts.Safety(0, false), List.of()));
        assertThat(gate.evaluate(safetyPass(), healthyStats(), noCalls, thresholds())
                .outcome()).isEqualTo(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY);
    }

    @Test
    @DisplayName("分支5：全过 → ELIGIBLE_FOR_CANARY 且 reasons 空")
    void branch5AllPassEligible() {
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), cleanRun(), thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY);
        assertThat(d.reasons()).isEmpty();
    }

    @Test
    @DisplayName("阈值版本化：version blank 拒绝构造（阈值随 ConfigBundle 走的版本锚）")
    void thresholdsVersionIsMandatory() {
        assertThatThrownBy(() -> new GateThresholds(" ", 0.05, 5, 60_000L, 100_000L, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateThresholds("v1", -0.01, 5, 60_000L, 100_000L, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateThresholds("v1", 0.05, 0, 60_000L, 100_000L, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateThresholds("v1", 0.05, 5, 0L, 100_000L, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateThresholds("v1", 0.05, 5, 60_000L, 0L, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateThresholds("v1", 0.05, 5, 60_000L, 100_000L, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 六维输入构造烟测（防 fixture 漂移）：真 EvalCaseInput → SixDimEvaluator → 门禁全过
    @Test
    @DisplayName("fixture 烟测：EvalCaseInput → 六维 → 门禁 ELIGIBLE 基线贯通")
    void fixtureBaselineStaysEligible() {
        EvalCaseInput in = new EvalCaseInput(
                new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                        "payment", EXPECTED, List.of("PAYMENT_CHARGE_FAILURE"),
                        new GoldenCase.Timing(1, 1, 1, 1, 1)),
                new EvidencePackageV2(2, "summary", EXPECTED,
                        List.of(new ReportClaim("root_cause", ClaimStatus.TRUE, "payment",
                                "BUSINESS_ERROR_RATE", List.of("PAYMENT_CHARGE_FAILURE"),
                                List.of("ref-1"))),
                        List.of("evidence-1"), "impact", "remediation", List.of()),
                List.of(new EvalCaseInput.ToolCallObservation("logs", true,
                        ToolCallStatus.SUCCESS, "d1")),
                List.of(), 100L, new EvalCaseInput.Usage(100L, 50L, 150L, false), false);
        SixDimResult run = new SixDimEvaluator(new ScenarioEvaluator(SynonymLexicon.load("""
                lexicon_version: 1
                components:
                  - code: payment
                    synonyms: [payment-svc]
                fault_types:
                  - code: BUSINESS_ERROR_RATE
                    synonyms: [business error rate]
                reason_codes:
                  - code: PAYMENT_CHARGE_FAILURE
                    fault_type: BUSINESS_ERROR_RATE
                    synonyms: [charge failure]
                """))).evaluate(in);
        GateDecision d = gate.evaluate(safetyPass(), healthyStats(), run, thresholds());
        assertThat(d.outcome()).isEqualTo(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY);
    }
}
