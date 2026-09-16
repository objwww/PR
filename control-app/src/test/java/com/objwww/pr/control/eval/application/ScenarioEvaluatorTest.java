package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-16 单场景评分纯函数（§6.4 root_cause_hit + symptom_coverage + silence_penalty）：
 * L1 矩阵——三维白名单命中/miss、UNRESOLVED 哨兵、TP/FP/FN、静默罚、症状规范化。
 * golden fixture = eval-scenarios.yml S1 同构（payment/BUSINESS_ERROR_RATE/PAYMENT_CHARGE_FAILURE）。
 */
class ScenarioEvaluatorTest {

    private static final SynonymLexicon LEX = SynonymLexicon.load("""
            lexicon_version: 1
            components:
              - code: payment
                synonyms: [payment-svc, 支付服务]
              - code: order-arena
                synonyms: [靶场]
            fault_types:
              - code: BUSINESS_ERROR_RATE
                synonyms: [business error rate, 业务错误率升高]
              - code: DEPENDENCY_UNREACHABLE
                synonyms: [依赖不可达]
            reason_codes:
              - code: PAYMENT_CHARGE_FAILURE
                fault_type: BUSINESS_ERROR_RATE
                synonyms: [扣款失败, charge failure]
              - code: PAYMENT_SERVICE_UNREACHABLE
                fault_type: DEPENDENCY_UNREACHABLE
                synonyms: [payment unreachable]
            """);

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private static GoldenCase golden(List<String> symptoms) {
        return new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment", EXPECTED, symptoms, new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    private static EvidencePackageV2 pkg(TypedRootCause cause, List<ReportClaim> claims) {
        return new EvidencePackageV2(2, "summary", cause, claims,
                List.of("evidence-1"), "impact", "remediation", List.of());
    }

    private static ReportClaim claim(List<String> symptomCodes) {
        return new ReportClaim("root_cause", ClaimStatus.TRUE, "payment",
                "BUSINESS_ERROR_RATE", symptomCodes, List.of("ref-1"));
    }

    private static final ScenarioEvaluator EVAL = new ScenarioEvaluator(LEX);

    @Test
    @DisplayName("命中：canonical 码 / 同义词 / 大小写空白规范化三维全中")
    void hitViaCanonicalAndSynonyms() {
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(EXPECTED, List.of(claim(List.of("checkout")))), true)
                .rootCauseHit()).isTrue();

        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("Payment-Svc", "business error rate", "扣款失败"),
                        List.of(claim(List.of("Checkout")))), true)
                .rootCauseHit()).isTrue();
    }

    @Test
    @DisplayName("miss：任一维度白名单外（含跨 fault_type 的 reason_code 从属拒绝）即不命中")
    void missWhenAnyDimensionOutsideWhitelist() {
        // reason_code 属 DEPENDENCY_UNREACHABLE，配 BUSINESS_ERROR_RATE 不算命中
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("payment", "BUSINESS_ERROR_RATE",
                        "PAYMENT_SERVICE_UNREACHABLE"), List.of(claim(List.of("checkout")))),
                true).rootCauseHit()).isFalse();
        // fault_type 白名单外
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("payment", "disk full", "PAYMENT_CHARGE_FAILURE"),
                        List.of(claim(List.of("checkout")))), true).rootCauseHit()).isFalse();
        // 白名单外不抛错（M-04）
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("kafka", "consumer lag", "whatever"),
                        List.of(claim(List.of("checkout")))), true).verdict())
                .isEqualTo(ScoringVerdict.DECIDABLE);
    }

    @Test
    @DisplayName("UNRESOLVED 哨兵：component=unresolved → 谨慎拒答，不进命中")
    void unresolvedSentinel() {
        ScenarioEvaluator.Evaluation ev = EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("UNRESOLVED ", "BUSINESS_ERROR_RATE",
                        "PAYMENT_CHARGE_FAILURE"), List.of(claim(List.of("checkout")))), true);
        assertThat(ev.verdict()).isEqualTo(ScoringVerdict.UNRESOLVED);
        assertThat(ev.rootCauseHit()).isFalse();
    }

    @Test
    @DisplayName("症状 TP/FP/FN：期望∩实际/多报/漏报；规范化去重后计数")
    void symptomTpFpFn() {
        ScenarioEvaluator.Evaluation ev = EVAL.evaluate(
                golden(List.of("Checkout", "checkout")),
                pkg(EXPECTED, List.of(
                        claim(List.of(" checkout ", "ArenaOrderStuck")),
                        claim(List.of("CHECKOUT")))),
                true);
        assertThat(ev.actualSymptomCodes()).containsExactly("checkout", "arenaorderstuck");
        assertThat(ev.truePositives()).isEqualTo(1);
        assertThat(ev.falsePositives()).isEqualTo(1);
        assertThat(ev.falseNegatives()).isZero();
    }

    @Test
    @DisplayName("silence_penalty：tool_calls 为空且 claims 非空 = 无证据出结论")
    void silencePenaltyWhenNoToolCallsButClaimsPresent() {
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(EXPECTED, List.of(claim(List.of("checkout")))), false)
                .silencePenalty()).isTrue();
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(EXPECTED, List.of(claim(List.of("checkout")))), true)
                .silencePenalty()).isFalse();
        assertThat(EVAL.evaluate(golden(List.of("checkout")),
                pkg(EXPECTED, List.of()), false).silencePenalty()).isFalse();
    }

    // ------------------------------------------------------------------ P3 三维评分

    @Test
    @DisplayName("P3 定因部分分：component 中/fault 中/reason 外 → 前两 true 第三 false")
    void partialCauseHits() {
        ScenarioEvaluator.Evaluation ev = EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("Payment-Svc", "业务错误率升高", "完全无关码"),
                        List.of(claim(List.of("checkout")))), true);
        assertThat(ev.componentHit()).isTrue();
        assertThat(ev.faultHit()).isTrue();
        assertThat(ev.reasonHit()).isFalse();
        assertThat(ev.rootCauseHit()).isFalse();
    }

    @Test
    @DisplayName("P3 定因逐维：全中=三 true；UNRESOLVED=三 null（未评不填 false）")
    void fullHitAndGetAllThreeTrueAndUnresolvedLeavesNulls() {
        ScenarioEvaluator.Evaluation hit = EVAL.evaluate(golden(List.of("checkout")),
                pkg(EXPECTED, List.of(claim(List.of("checkout")))), true);
        assertThat(hit.componentHit()).isTrue();
        assertThat(hit.faultHit()).isTrue();
        assertThat(hit.reasonHit()).isTrue();

        ScenarioEvaluator.Evaluation unresolved = EVAL.evaluate(golden(List.of("checkout")),
                pkg(new TypedRootCause("UNRESOLVED", "BUSINESS_ERROR_RATE",
                        "PAYMENT_CHARGE_FAILURE"), List.of(claim(List.of("checkout")))), true);
        assertThat(unresolved.componentHit()).isNull();
        assertThat(unresolved.faultHit()).isNull();
        assertThat(unresolved.reasonHit()).isNull();
    }

    @Test
    @DisplayName("P3 路径维：检查点按 casefold 子串命中报告语料；无检查点=空表")
    void checkpointMatchesAgainstPackageCorpus() {
        EvidencePackageV2 rich = new EvidencePackageV2(2,
                "order-arena 卡单 oa_stuck_orders_current 持续走高", EXPECTED,
                List.of(claim(List.of("checkout"))),
                List.of("evidence logs.query 命中 payment timeout"), "impact", "remediation",
                List.of());
        GoldenCase withCheckpoints = new GoldenCase("S1", "n", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("checkout"),
                java.util.Map.of(), null,
                new GoldenCase.Timing(1, 1, 1, 1, 1),
                GoldenCase.KIND_INJECT,
                List.of("OA_STUCK_ORDERS", "logs.query", "不存在点"));
        List<ScenarioEvaluator.CheckpointMatch> matches =
                EVAL.checkpointMatches(withCheckpoints, rich);
        assertThat(matches).hasSize(3);
        assertThat(matches.get(0).matched()).isTrue();
        assertThat(matches.get(1).matched()).isTrue();
        assertThat(matches.get(2).matched()).isFalse();

        assertThat(EVAL.checkpointMatches(golden(List.of("checkout")), rich)).isEmpty();
    }

    @Test
    @DisplayName("P3 结论复核：TRUE 根因 claim 带引用=GROUNDED，无引用=UNGROUNDED，拒答=NA")
    void conclusionGroundedClassification() {
        assertThat(EVAL.conclusionGrounded(pkg(EXPECTED,
                List.of(claim(List.of("checkout")))))).isEqualTo("GROUNDED");

        assertThat(EVAL.conclusionGrounded(pkg(EXPECTED, List.of(new ReportClaim(
                        "root_cause", ClaimStatus.TRUE, "payment", "BUSINESS_ERROR_RATE",
                        List.of("checkout"), List.of())))))
                .isEqualTo("UNGROUNDED");

        assertThat(EVAL.conclusionGrounded(pkg(new TypedRootCause("unresolved", "f", "r"),
                List.of(claim(List.of("checkout")))))).isEqualTo("NOT_APPLICABLE");
    }
}
