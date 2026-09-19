package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.application.NativeReportAdapter;
import com.objwww.pr.control.alert.domain.claim.ClaimKind;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.claim.ReportAssembler;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根因评分贯通链验证（告警-Agent 根因评分贯通修复，缺陷 1 闭环证明）：
 * 主 Agent FINAL 提案的 root_cause 三元组 → 检查点 → 投影 → ClaimVerdict →
 * NativeReportAdapter 直填 EvidencePackage v2 root_cause → ScenarioEvaluator
 * 词表等值命中。本测试覆盖链路的最后两棒（适配 + 评分）：Agent 答对（提案携带
 * S1 canonical 三元组）→ 评分必须命中，不再被 "primary"/"c1" 冒充面恒 miss。
 *
 * <p>词表用测试资源 eval/synonym-lexicon-v1.yml（与 deploy 同名文件同构的最小
 * 词典——加载路径同源证明"同一份词表文件"接线成立）；GT 与 eval-scenarios.yml
 * S1 同构（payment/BUSINESS_ERROR_RATE/PAYMENT_CHARGE_FAILURE）。
 */
class RootCauseScoringChainTest {

    private static final String SNAPSHOT = "ab".repeat(32);

    /** S1 GT（eval-scenarios.yml 同构） */
    private static final TypedRootCause S1_GT =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private static SynonymLexicon loadLexicon() throws Exception {
        try (var in = RootCauseScoringChainTest.class
                .getResourceAsStream("/eval/synonym-lexicon-v1.yml")) {
            assertThat(in).as("测试词表在 classpath").isNotNull();
            return SynonymLexicon.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Agent 答对（确认根因携带 S1 canonical 三元组）→ 三维全 hit、root_cause_hit=true")
    void correctAgentAnswerScoresHit() throws Exception {
        // 投影面产物：确认根因 claim 携带结构化三元组（V147 贯通面）
        ClaimVerdict confirmed = new ClaimVerdict("c1", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"), "payment 扣款按比例失败（两源一致）",
                List.of("e1", "e2"), "r7-primary-v2", ClaimKind.ROOT_CAUSE, S1_GT);
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(confirmed), List.of(), List.of(), 0);

        // 适配面：直填 canonical 三元组（不再拿 scope="primary"/claimKey="c1" 冒充）
        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result validated =
                new EvidencePackageValidator(65_536, 32, 4_096)
                        .validate(adapted.outerJson());
        assertThat(validated.status().name()).isEqualTo("STRUCTURE_VALIDATED");
        EvidencePackageV2 pkg = validated.typedPackage();
        assertThat(pkg.rootCause()).isEqualTo(S1_GT);

        // 评分面：词表等值三维全中
        GoldenCase golden = new GoldenCase("S1", "paymentFailure=50%",
                "FlagdScenarioDriver", null, "payment", S1_GT, List.of(),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
        ScenarioEvaluator.Evaluation evaluation =
                new ScenarioEvaluator(loadLexicon()).evaluate(golden, pkg, true);

        assertThat(evaluation.componentHit()).isTrue();
        assertThat(evaluation.faultHit()).isTrue();
        assertThat(evaluation.reasonHit()).isTrue();
        assertThat(evaluation.rootCauseHit())
                .as("Agent 答对 → 评分命中（缺陷 1 修复前结构性恒 miss）")
                .isTrue();
    }

    @Test
    @DisplayName("对照：确认根因未携带三元组 → 诚实 unknown → 词表外如实 miss（不冒充命中）")
    void missingTripleStaysHonestMiss() throws Exception {
        ClaimVerdict confirmed = new ClaimVerdict("c1", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"), "证据不足未定型",
                List.of("e1", "e2"), "r7-primary-v2", ClaimKind.ROOT_CAUSE);
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(confirmed), List.of(), List.of(), 0);

        EvidencePackageV2 pkg = new EvidencePackageValidator(65_536, 32, 4_096)
                .validate(NativeReportAdapter.adapt(report).outerJson()).typedPackage();
        assertThat(pkg.rootCause().component()).isEqualTo("unknown");
        GoldenCase golden = new GoldenCase("S1", "paymentFailure=50%",
                "FlagdScenarioDriver", null, "payment", S1_GT, List.of(),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
        ScenarioEvaluator.Evaluation evaluation =
                new ScenarioEvaluator(loadLexicon()).evaluate(golden, pkg, true);

        assertThat(evaluation.rootCauseHit())
                .as("诚实 unknown 词表外（M-04 NO_MATCH 不抛错），如实记 miss")
                .isFalse();
    }
}
