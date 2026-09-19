package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.application.ReportWritingRubric;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;

import java.util.Optional;

/**
 * 根因结论六要素检出器（M-d T5 前置，纪律见 .agent-notes/根因结论六要素纪律.md）。
 *
 * <p>口径（结构面优先，文本面兜底）：发生了什么=summary 非空；根因=root_cause 三元组
 * 三字段全非 blank；凭什么=claims 非空且至少一条带 evidence_refs；影响多大=impact
 * 非空；建议怎么办=remediation 非空；有多大把握=共享规约
 * {@link ReportWritingRubric#confidenceLevelOf} 文本检出（认 agent 原文把握硬措辞
 * 「把握：HIGH|MEDIUM|LOW」与 BA-175 确定性摘要「结论置信度：高/中/未定论」两种锚，
 * 摘要/影响面兜底）。
 *
 * <p>纯函数零副作用；评分接线（T5）在 SingleCaseScorer 调用后落
 * eval_case_result.conclusion_six_parts 并聚合 six_parts_rate。检不出≠造假填充——
 * 六布尔如实，全绿才算六要素齐。
 */
public final class SixElementsChecker {

    /** 检出结果：六布尔 + 把握档位（检不出为 empty——不猜） */
    public record Result(boolean whatHappened, boolean rootCause, boolean evidenceBasis,
                         boolean impact, boolean confidence, boolean recommendation,
                         Optional<String> confidenceLevel) {

        /** 六要素齐 = 全真（把握短语与档位同时在场） */
        public boolean complete() {
            return whatHappened && rootCause && evidenceBasis
                    && impact && confidence && recommendation;
        }
    }

    private SixElementsChecker() {
    }

    /** 结构面 + 报告原文（raw_text，可空——空则回退摘要/影响面文本兜底） */
    public static Result check(EvidencePackageV2 pkg, String reportText) {
        boolean what = pkg.summary() != null && !pkg.summary().isBlank();
        boolean root = pkg.rootCause() != null
                && notBlank(pkg.rootCause().component())
                && notBlank(pkg.rootCause().faultType())
                && notBlank(pkg.rootCause().reasonCode());
        boolean basis = !pkg.claims().isEmpty() && pkg.claims().stream()
                .anyMatch(c -> c.evidenceRefs() != null && !c.evidenceRefs().isEmpty());
        boolean impact = pkg.impact() != null && !pkg.impact().isBlank();
        boolean rec = pkg.remediation() != null && !pkg.remediation().isBlank();

        String haystack = (reportText == null || reportText.isBlank())
                ? String.join("\n", nullSafe(pkg.summary()), nullSafe(pkg.impact()))
                : reportText;
        Optional<String> level = ReportWritingRubric.confidenceLevelOf(haystack);
        return new Result(what, root, basis, impact, level.isPresent(), rec, level);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
