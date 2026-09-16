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

    /** 结论有据性词表（P3 结论复核维；与 EvalCaseResult 同值域） */
    public static final String GROUNDED = "GROUNDED";
    public static final String UNGROUNDED = "UNGROUNDED";
    public static final String GROUNDED_NA = "NOT_APPLICABLE";

    private final SynonymLexicon lexicon;

    public ScenarioEvaluator(SynonymLexicon lexicon) {
        this.lexicon = lexicon;
    }

    /**
     * 逐维度评分结果（verdict 只会是 DECIDABLE/UNRESOLVED；失败/缺席由 scorer 分派）。
     * P3：componentHit/faultHit/reasonHit = 定因逐维同义词命中（仅 DECIDABLE 有值，
     * 否则 null 未评——全中即 rootCauseHit，部分中=部分分的图距离适配）。
     */
    public record Evaluation(ScoringVerdict verdict,
                             boolean rootCauseHit,
                             Boolean componentHit,
                             Boolean faultHit,
                             Boolean reasonHit,
                             List<String> actualSymptomCodes,
                             int truePositives,
                             int falsePositives,
                             int falseNegatives,
                             boolean silencePenalty) {

        /** M3-16 兼容构造（P3 前调用面）：定因逐维 = null（未评） */
        public Evaluation(ScoringVerdict verdict, boolean rootCauseHit,
                          List<String> actualSymptomCodes, int truePositives,
                          int falsePositives, int falseNegatives, boolean silencePenalty) {
            this(verdict, rootCauseHit, null, null, null, actualSymptomCodes,
                    truePositives, falsePositives, falseNegatives, silencePenalty);
        }
    }

    /** 单个 GT 证据检查点的命中结果（P3 路径维；纯数据） */
    public record CheckpointMatch(String checkpoint, boolean matched) {
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
                    null, null, null,
                    List.copyOf(actual), tp, fp, fn,
                    !toolCallsPresent && !pkg.claims().isEmpty());
        }
        boolean componentHit = lexicon.componentMatches(expectedCause.component(),
                actualCause.component());
        boolean faultHit = lexicon.faultTypeMatches(expectedCause.faultType(),
                actualCause.faultType());
        boolean reasonHit = lexicon.reasonCodeMatches(expectedCause.reasonCode(),
                expectedCause.faultType(), actualCause.reasonCode());
        boolean hit = componentHit && faultHit && reasonHit;
        return new Evaluation(ScoringVerdict.DECIDABLE, hit,
                componentHit, faultHit, reasonHit,
                List.copyOf(actual), tp, fp, fn,
                !toolCallsPresent && !pkg.claims().isEmpty());
    }

    /**
     * P3 路径维：GT 证据检查点覆盖（纯函数）。语料 = 报告包全文面（summary/
     * 逐 claim 的 component/fault_type/症状码/impact/remediation/evidence 文本），
     * 检查点按规范化子串匹配（trim+casefold）。无检查点 = 空表（NOT_APPLICABLE）。
     */
    public List<CheckpointMatch> checkpointMatches(GoldenCase golden, EvidencePackageV2 pkg) {
        if (golden.expectedEvidenceCheckpoints().isEmpty()) {
            return List.of();
        }
        StringBuilder corpus = new StringBuilder(normalize(pkg.summary()))
                .append('\n').append(normalize(pkg.impact()))
                .append('\n').append(normalize(pkg.remediation()));
        pkg.evidence().forEach(e -> corpus.append('\n').append(normalize(e)));
        pkg.claims().forEach(c -> {
            corpus.append('\n').append(normalize(c.component()))
                    .append('\n').append(normalize(c.faultType()));
            c.symptomCodes().forEach(s -> corpus.append('\n').append(normalize(s)));
            c.evidenceRefs().forEach(r -> corpus.append('\n').append(normalize(r)));
        });
        String hay = corpus.toString();
        return golden.expectedEvidenceCheckpoints().stream()
                .map(cp -> new CheckpointMatch(cp, hay.contains(normalize(cp))))
                .toList();
    }

    /**
     * P3 结论复核维（纯函数）：结论为 TRUE 的根因 claim 是否携带证据引用——
     * 全带 = GROUNDED；有 TRUE 根因 claim 而无任何引用 = UNGROUNDED
     * （OpenRCA 2.0 ungrounded diagnosis 探针）；谨慎拒答/无 TRUE 根因 claim =
     * NOT_APPLICABLE（无结论可复核，不是有据）。
     */
    public String conclusionGrounded(EvidencePackageV2 pkg) {
        if (normalize(pkg.rootCause().component()).equals(UNRESOLVED_COMPONENT)) {
            return GROUNDED_NA;
        }
        boolean anyTrueRootCause = false;
        boolean allGrounded = true;
        for (var claim : pkg.claims()) {
            if ("root_cause".equals(claim.claimType())
                    && claim.status() == com.objwww.pr.control.alert.domain.claim.ClaimStatus.TRUE) {
                anyTrueRootCause = true;
                if (claim.evidenceRefs().isEmpty()) {
                    allGrounded = false;
                }
            }
        }
        if (!anyTrueRootCause) {
            return GROUNDED_NA;
        }
        return allGrounded ? GROUNDED : UNGROUNDED;
    }

    /** 期望/实际症状码统一规范化（M-03 同法：trim + ASCII casefold） */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
