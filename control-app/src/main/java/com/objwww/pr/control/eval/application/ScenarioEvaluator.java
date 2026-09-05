package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.SynonymLexicon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单场景评分纯函数（M3-16；§6.4 root_cause_hit + symptom_coverage + silence_penalty）。
 * 不调 LLM、不碰 DB/HTTP（§7 L0 分层断言面）——输入已解析的 EvidencePackage v2。
 *
 * <p>冻结语义：
 * <ul>
 *   <li>root_cause_hit = component/fault_type/reason_code 三维全部命中同义词白名单
 *       （reason_code 额外校验从属 fault_type）；白名单外 = miss 不抛错（M-04）；</li>
 *   <li><b>UNRESOLVED 哨兵</b>：root_cause.component 规范化后等于 {@code unresolved}
 *       = 谨慎拒答（v2 结构上 root_cause 必填，拒答只能经哨兵表达——本约定为
 *       落码新定，待主会话确认/词典 v2 正式化）；</li>
 *   <li>symptom TP/FP/FN：期望症状码 × claims[].symptom_codes 并集（规范化去重）——
 *       TP=∩、FP=多报、FN=漏报；</li>
 *   <li>silence_penalty：无 tool_calls 而 claims 非空（无证据出结论）。</li>
 * </ul>
 */
public final class ScenarioEvaluator {

    /** 谨慎拒答哨兵（root_cause.component 规范化等值；见类注） */
    public static final String UNRESOLVED_COMPONENT = "unresolved";

    private final SynonymLexicon lexicon;

    public ScenarioEvaluator(SynonymLexicon lexicon) {
        this.lexicon = lexicon;
    }

    /** 逐维度评分结果（verdict 只会是 DECIDABLE/UNRESOLVED；失败/缺席由 scorer 分派） */
    public record Evaluation(ScoringVerdict verdict,
                             boolean rootCauseHit,
                             List<String> actualSymptomCodes,
                             int truePositives,
                             int falsePositives,
                             int falseNegatives,
                             boolean silencePenalty) {
    }

    public Evaluation evaluate(GoldenCase golden, EvidencePackageV2 pkg,
                               boolean toolCallsPresent) {
        Set<String> expected = new LinkedHashSet<>();
        golden.expectedSymptomCodes().forEach(s -> expected.add(normalize(s)));
        Set<String> actual = new LinkedHashSet<>();
        pkg.claims().forEach(c -> c.symptomCodes().forEach(s -> actual.add(normalize(s))));

        int tp = 0;
        for (String code : expected) {
            if (actual.contains(code)) {
                tp++;
            }
        }
        int fp = actual.size() - tp;
        int fn = expected.size() - tp;

        TypedRootCause expectedCause = golden.expectedRootCause();
        TypedRootCause actualCause = pkg.rootCause();
        if (normalize(actualCause.component()).equals(UNRESOLVED_COMPONENT)) {
            return new Evaluation(ScoringVerdict.UNRESOLVED, false,
                    List.copyOf(actual), tp, fp, fn,
                    !toolCallsPresent && !pkg.claims().isEmpty());
        }
        boolean hit = lexicon.componentMatches(expectedCause.component(), actualCause.component())
                && lexicon.faultTypeMatches(expectedCause.faultType(), actualCause.faultType())
                && lexicon.reasonCodeMatches(expectedCause.reasonCode(),
                        expectedCause.faultType(), actualCause.reasonCode());
        return new Evaluation(ScoringVerdict.DECIDABLE, hit,
                List.copyOf(actual), tp, fp, fn,
                !toolCallsPresent && !pkg.claims().isEmpty());
    }

    /** 期望/实际症状码统一规范化（M-03 同法：trim + ASCII casefold） */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
