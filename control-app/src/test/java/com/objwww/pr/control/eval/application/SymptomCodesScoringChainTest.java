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
 * 症状码评分贯通链验证（BA-148 同族修复，批 aa7f25b4 tp=0/fp=99/fn=60 闭环证明）：
 * 主 Agent FINAL 提案的 symptom_codes（告警名）→ 检查点 → 投影 → ClaimVerdict →
 * NativeReportAdapter 直填 EvidencePackage v2 claims[].symptom_codes →
 * ScenarioEvaluator symptom_coverage（期望症状码 × 并集等值）命中。
 * 本测试覆盖链路的最后两棒（适配 + 评分）：SYMPTOM claim 携带告警名 →
 * tp=1/fp=0/fn=0；旧实现把证据来源标签（logs/prometheus）塞进该槽位 = 结构性
 * 恒 miss（对照用例钉死诚实 miss，不冒充命中）。
 *
 * <p>词表加载路径与 RootCauseScoringChainTest 同源（测试资源
 * eval/synonym-lexicon-v1.yml）。
 */
class SymptomCodesScoringChainTest {

    private static final String SNAPSHOT = "ab".repeat(32);

    private static SynonymLexicon loadLexicon() throws Exception {
        try (var in = SymptomCodesScoringChainTest.class
                .getResourceAsStream("/eval/synonym-lexicon-v1.yml")) {
            assertThat(in).as("测试词表在 classpath").isNotNull();
            return SynonymLexicon.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static GoldenCase golden() {
        return new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment",
                new TypedRootCause("payment", "BUSINESS_ERROR_RATE",
                        "PAYMENT_CHARGE_FAILURE"),
                List.of("ArenaDuplicateOrders"),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    @Test
    @DisplayName("SYMPTOM claim 带 symptom_codes=[告警名] → 报告包 claims[].symptom_codes 直填，评分 tp=1/fp=0/fn=0")
    void declaredSymptomCodeScoresHit() throws Exception {
        // 投影面产物：SYMPTOM claim 携带症状码（V151 贯通面）
        ClaimVerdict symptom = new ClaimVerdict("c1", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"), "ArenaDuplicateOrders firing（两源一致）",
                List.of("e1", "e2"), "r7-primary-v2", ClaimKind.SYMPTOM,
                null, List.of("ArenaDuplicateOrders"));
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.PARTIAL, SNAPSHOT,
                List.of(), List.of(symptom), List.of(), 0);

        // 适配面：symptom_codes=症状码评分面（不再拿 sources 来源标签冒充）
        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result validated =
                new EvidencePackageValidator(65_536, 32, 4_096)
                        .validate(adapted.outerJson());
        assertThat(validated.status().name()).isEqualTo("STRUCTURE_VALIDATED");
        EvidencePackageV2 pkg = validated.typedPackage();
        assertThat(pkg.claims().get(0).symptomCodes())
                .containsExactly("ArenaDuplicateOrders");

        // 评分面：期望症状码等值命中
        ScenarioEvaluator.Evaluation evaluation =
                new ScenarioEvaluator(loadLexicon()).evaluate(golden(), pkg, true);

        assertThat(evaluation.truePositives()).isEqualTo(1);
        assertThat(evaluation.falsePositives())
                .as("来源标签不再进 symptom_codes 槽位 → 零误报（修复前 fp=99）")
                .isZero();
        assertThat(evaluation.falseNegatives()).isZero();
    }

    @Test
    @DisplayName("对照：未声明症状码 → 诚实空数组 → 如实 fn=1（不拿来源标签冒充命中）")
    void missingSymptomCodesStaysHonestMiss() throws Exception {
        ClaimVerdict symptom = new ClaimVerdict("c1", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"), "症状未定型",
                List.of("e1", "e2"), "r7-primary-v2", ClaimKind.SYMPTOM);
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.PARTIAL, SNAPSHOT,
                List.of(), List.of(symptom), List.of(), 0);

        EvidencePackageV2 pkg = new EvidencePackageValidator(65_536, 32, 4_096)
                .validate(NativeReportAdapter.adapt(report).outerJson()).typedPackage();
        assertThat(pkg.claims().get(0).symptomCodes()).isEmpty();

        ScenarioEvaluator.Evaluation evaluation =
                new ScenarioEvaluator(loadLexicon()).evaluate(golden(), pkg, true);

        assertThat(evaluation.truePositives()).isZero();
        assertThat(evaluation.falsePositives()).isZero();
        assertThat(evaluation.falseNegatives())
                .as("诚实空数组如实记 fn（旧实现来源标签冒充 → tp=0/fp=99/fn=60）")
                .isEqualTo(1);
    }
}
