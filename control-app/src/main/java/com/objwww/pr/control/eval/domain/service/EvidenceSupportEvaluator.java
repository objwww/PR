package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.EvidenceJudgeInput;
import com.objwww.pr.control.eval.domain.model.EvidenceSupportEvaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 证据支持/摘要忠实/任务约束评测纯函数（ME-T09/D09 步骤 1 + JUDGE-01/03 矩阵；
 * L0：不调 LLM、不碰 DB/HTTP——版本化 rubric 输入契约的机制面，真实语义裁判
 * 跑批归后续专项，本评估器聚合的是外部裁决后的支持关系观测）。
 *
 * <p>与报告四题（judge-rubric-v2，「报告质量」维）并列不替代：报告写作可
 * 通过而证据支持不过（JUDGE-01）——两面分别出数，不让报告自证正确。
 * 三项检查（缺证据落 NOT_ASSESSED = UNKNOWN 面，不凭引用 ID 猜事实，JUDGE-03）：
 * <ul>
 *   <li>{@value #CHECK_EVIDENCE_SUPPORT}：断言级证据支持——CONTRADICTS/UNRELATED
 *       = FAIL（确证不支撑优先于 UNKNOWN）；无证据断言/引用未解析/证据缺原文
 *       = NOT_ASSESSED（UNKNOWN 不定败）；全 SUPPORTS = PASS；</li>
 *   <li>{@value #CHECK_SUMMARY_FIDELITY}：摘要/结论段断言（inSummary）必须全部
 *       被证据支持——摘要断言不支撑 = FAIL，不可核验 = NOT_ASSESSED；</li>
 *   <li>{@value #CHECK_CONSTRAINT_COMPLIANCE}：任务约束逐条——违例 = FAIL，
 *       未观测 = NOT_ASSESSED，全满足 = PASS。</li>
 * </ul>
 */
public final class EvidenceSupportEvaluator {

    /** 证据 rubric 版本锚（改检查口径必须升版，校准一致率才有锚） */
    public static final String RUBRIC_VERSION = "evidence-rubric-v1";

    public static final String CHECK_EVIDENCE_SUPPORT = "evidence_support";
    public static final String CHECK_SUMMARY_FIDELITY = "summary_fidelity";
    public static final String CHECK_CONSTRAINT_COMPLIANCE = "task_constraint_compliance";

    private final String rubricVersion;

    public EvidenceSupportEvaluator() {
        this(RUBRIC_VERSION);
    }

    /** 显式 rubric 版本（并存重评：新版本新行，旧记录不覆盖——同 ME-T04 纪律） */
    public EvidenceSupportEvaluator(String rubricVersion) {
        this.rubricVersion = Objects.requireNonNull(rubricVersion, "rubricVersion 不得为 null");
    }

    public EvidenceSupportEvaluation evaluate(EvidenceJudgeInput in) {
        Objects.requireNonNull(in, "in 不得为 null");
        Map<String, EvidenceJudgeInput.EvidenceItem> evidenceById = in.evidence().stream()
                .collect(Collectors.toMap(EvidenceJudgeInput.EvidenceItem::evidenceId,
                        Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        List<BehaviorEvaluation.Check> checks = new ArrayList<>();
        List<BehaviorEvaluation.Metric> metrics = new ArrayList<>();
        List<String> failureLabels = new ArrayList<>();

        // ---------------- 证据支持（JUDGE-01/JUDGE-03） ----------------
        if (in.claims().isEmpty()) {
            checks.add(check(CHECK_EVIDENCE_SUPPORT, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_CLAIMS"));
        } else {
            List<String> failReasons = new ArrayList<>();
            List<String> unknownReasons = new ArrayList<>();
            long supported = 0;
            for (EvidenceJudgeInput.ClaimObservation claim : in.claims()) {
                if (claim.evidenceId() == null) {
                    unknownReasons.add("CLAIM_WITHOUT_EVIDENCE");
                    continue;
                }
                EvidenceJudgeInput.EvidenceItem evidence = evidenceById.get(claim.evidenceId());
                if (evidence == null) {
                    unknownReasons.add("CITATION_UNRESOLVED");
                    continue;
                }
                if (!evidence.hasText()) {
                    // 只有引用 ID 没有原文：支持性 UNKNOWN，不凭 ID 猜测事实
                    unknownReasons.add("EVIDENCE_TEXT_MISSING");
                    continue;
                }
                switch (claim.relation()) {
                    case CONTRADICTS -> failReasons.add("EVIDENCE_CONTRADICTS_CLAIM");
                    case UNRELATED -> failReasons.add("EVIDENCE_DOES_NOT_SUPPORT");
                    case SUPPORTS -> supported++;
                }
            }
            metrics.add(new BehaviorEvaluation.Metric(
                    "claims_supported", supported, in.claims().size()));
            if (!failReasons.isEmpty()) {
                String reason = failReasons.contains("EVIDENCE_CONTRADICTS_CLAIM")
                        ? "EVIDENCE_CONTRADICTS_CLAIM" : "EVIDENCE_DOES_NOT_SUPPORT";
                checks.add(check(CHECK_EVIDENCE_SUPPORT, BehaviorCheckStatus.FAIL, reason));
                failureLabels.addAll(failReasons.stream().distinct().toList());
            } else if (!unknownReasons.isEmpty()) {
                checks.add(check(CHECK_EVIDENCE_SUPPORT, BehaviorCheckStatus.NOT_ASSESSED,
                        unknownReasons.get(0)));
            } else {
                checks.add(check(CHECK_EVIDENCE_SUPPORT, BehaviorCheckStatus.PASS,
                        "ALL_CLAIMS_SUPPORTED"));
            }
        }

        // ---------------- 摘要忠实（摘要段断言不得超出证据支持面） ----------------
        List<EvidenceJudgeInput.ClaimObservation> summaryClaims = in.claims().stream()
                .filter(EvidenceJudgeInput.ClaimObservation::inSummary).toList();
        if (in.claims().isEmpty() || summaryClaims.isEmpty()) {
            checks.add(check(CHECK_SUMMARY_FIDELITY, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_SUMMARY_CLAIMS"));
        } else {
            boolean unsupported = summaryClaims.stream().anyMatch(c ->
                    c.relation() == EvidenceJudgeInput.SupportRelation.CONTRADICTS
                            || c.relation() == EvidenceJudgeInput.SupportRelation.UNRELATED);
            boolean unverifiable = summaryClaims.stream().anyMatch(c ->
                    c.evidenceId() == null || !evidenceById.containsKey(c.evidenceId())
                            || !evidenceById.get(c.evidenceId()).hasText());
            if (unsupported) {
                checks.add(check(CHECK_SUMMARY_FIDELITY, BehaviorCheckStatus.FAIL,
                        "SUMMARY_CLAIM_UNSUPPORTED"));
                failureLabels.add("SUMMARY_CLAIM_UNSUPPORTED");
            } else if (unverifiable) {
                checks.add(check(CHECK_SUMMARY_FIDELITY, BehaviorCheckStatus.NOT_ASSESSED,
                        "SUMMARY_CLAIM_UNVERIFIABLE"));
            } else {
                checks.add(check(CHECK_SUMMARY_FIDELITY, BehaviorCheckStatus.PASS,
                        "SUMMARY_WITHIN_SUPPORT"));
            }
        }

        // ---------------- 任务约束遵守 ----------------
        if (in.constraints().isEmpty()) {
            checks.add(check(CHECK_CONSTRAINT_COMPLIANCE, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NO_TASK_CONSTRAINTS"));
        } else if (in.constraints().stream().anyMatch(c -> Boolean.FALSE.equals(c.satisfied()))) {
            checks.add(check(CHECK_CONSTRAINT_COMPLIANCE, BehaviorCheckStatus.FAIL,
                    "TASK_CONSTRAINT_VIOLATED"));
            failureLabels.add("TASK_CONSTRAINT_VIOLATED");
        } else if (in.constraints().stream().anyMatch(c -> c.satisfied() == null)) {
            checks.add(check(CHECK_CONSTRAINT_COMPLIANCE, BehaviorCheckStatus.NOT_ASSESSED,
                    "CONSTRAINT_UNOBSERVED"));
        } else {
            checks.add(check(CHECK_CONSTRAINT_COMPLIANCE, BehaviorCheckStatus.PASS,
                    "ALL_CONSTRAINTS_SATISFIED"));
        }

        return new EvidenceSupportEvaluation(rubricVersion, checks, metrics,
                failureLabels.stream().distinct().toList());
    }

    private static BehaviorEvaluation.Check check(String name, BehaviorCheckStatus status,
                                                  String reason) {
        return new BehaviorEvaluation.Check(name, status, reason, List.of());
    }
}
