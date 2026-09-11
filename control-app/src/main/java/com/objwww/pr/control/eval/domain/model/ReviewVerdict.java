package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * EV-08 评审结果（review_verdict 行投影，insert-only）：评分/标签/理由 +
 * 冻结 rubric 版本 + 提交时刻。一任务一结论（uq(assignment_id)）；更正与
 * 重评分 = 新任务 + 新行，旧行永不覆盖（§3.6 审计闭环，不覆盖原机器评分）。
 *
 * <p>verdict 封闭值域（§3.6 不要求审核者强行二选一）：CORRECT/PARTIAL/INCORRECT
 * + UNDECIDABLE（无法判定）/INSUFFICIENT_SOURCE（来源不足）一等结论。
 * score 可空（无法判定不打分）；reason 必填（DB CHECK 兜底，服务层先验）。
 */
public record ReviewVerdict(UUID id, UUID assignmentId, UUID runId, UUID caseExecutionId,
                            String reviewer, String rubricVersion, Verdict verdict,
                            Integer score, List<String> labels, String reason,
                            List<String> evidenceRefs, Instant createdAt) {

    public enum Verdict {CORRECT, PARTIAL, INCORRECT, UNDECIDABLE, INSUFFICIENT_SOURCE}

    public ReviewVerdict {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(caseExecutionId, "caseExecutionId");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(rubricVersion, "rubricVersion");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
        labels = labels == null ? List.of() : List.copyOf(labels);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
