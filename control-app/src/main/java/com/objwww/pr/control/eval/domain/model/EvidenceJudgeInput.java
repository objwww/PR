package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 证据支持裁判的版本化输入契约（ME-T09/D09 步骤 1；纯数据，L0 零框架依赖）。
 *
 * <p>报告四题（judge-rubric-v2）保留为「报告质量」维，本契约是与之并列的
 * <b>证据面</b>输入：裁判不再只看报告正文自证正确，输入可携带原始证据
 * （{@link EvidenceItem} 的 id + 原文）与任务约束（{@link ConstraintObservation}）。
 * 事实断言的支持关系（{@link ClaimObservation#relation()}）由标注/裁判外部裁决
 * 后作为观测输入——本模型只做结构校验与缺证据标记，不做语义猜测：
 * 只有引用 ID 没有原文 → 支持性 UNKNOWN（JUDGE-03），不凭 ID 猜事实。
 */
public record EvidenceJudgeInput(String reportText,
                                 List<EvidenceItem> evidence,
                                 List<ClaimObservation> claims,
                                 List<ConstraintObservation> constraints) {

    public EvidenceJudgeInput {
        Objects.requireNonNull(reportText, "reportText 不得为 null");
        Objects.requireNonNull(evidence, "evidence 不得为 null");
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(claims, "claims 不得为 null");
        claims = List.copyOf(claims);
        Objects.requireNonNull(constraints, "constraints 不得为 null");
        constraints = List.copyOf(constraints);
    }

    /** 一条原始证据：text 为 null/blank = 只有引用 ID 没有原文（UNKNOWN 面，不猜） */
    public record EvidenceItem(String evidenceId, String text) {

        public EvidenceItem {
            Objects.requireNonNull(evidenceId, "evidenceId 不得为 null");
            if (evidenceId.isBlank()) {
                throw new IllegalArgumentException("evidenceId 不得为 blank");
            }
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /** 断言与所引证据的支持关系（外部裁决观测；UNKNOWN 不由本词表承载——
     *  证据缺席/原文缺失由结构字段表达，评估器据此落 NOT_ASSESSED） */
    public enum SupportRelation {
        /** 证据支持该断言 */
        SUPPORTS,
        /** 证据与断言相反（JUDGE-01：文笔完整但证据矛盾 → 证据支持 FAIL） */
        CONTRADICTS,
        /** 所引证据与断言无关（引用不支撑 = 支持不成立） */
        UNRELATED
    }

    /**
     * 一条事实断言的支持观测：evidenceId 为 null = 断言无任何证据（信息不足，
     * UNKNOWN 不定败）；inSummary = 该断言出现在摘要/结论段（摘要忠实面输入）。
     */
    public record ClaimObservation(String claimId, String evidenceId,
                                   SupportRelation relation, boolean inSummary) {

        public ClaimObservation {
            Objects.requireNonNull(claimId, "claimId 不得为 null");
            if (claimId.isBlank()) {
                throw new IllegalArgumentException("claimId 不得为 blank");
            }
            // evidenceId 为 null 时 relation 无观测对象，必须为 null（不猜支持方向）
            if (evidenceId == null && relation != null) {
                throw new IllegalArgumentException("无证据断言不得携带支持关系: " + claimId);
            }
            if (evidenceId != null) {
                Objects.requireNonNull(relation, "引用证据的断言必带支持关系: " + claimId);
            }
        }
    }

    /** 一条任务约束的满足观测：satisfied 为 null = 未观测（UNKNOWN，不猜通过） */
    public record ConstraintObservation(String constraintId, Boolean satisfied) {

        public ConstraintObservation {
            Objects.requireNonNull(constraintId, "constraintId 不得为 null");
            if (constraintId.isBlank()) {
                throw new IllegalArgumentException("constraintId 不得为 blank");
            }
        }
    }
}
