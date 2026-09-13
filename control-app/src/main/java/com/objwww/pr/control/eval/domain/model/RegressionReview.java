package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 回归候选的独立审核意见（OP-01，V104 rca_regression_review append-only）：
 * (candidate, reviewer) 唯一——同一审核者重投幂等收敛；不同审核者意见可并存，
 * 相异结论触发候选 DISPUTED。机器分数/用户反馈/审核结论三者分存（§5.2），
 * 审核 ≠ 自动采信。
 */
public record RegressionReview(UUID id, UUID candidateId, String reviewer,
        String verdict, String reason, Instant createdAt) {

    public static final String ACCEPTED_FOR_CANDIDATE = "ACCEPTED_FOR_CANDIDATE";
    public static final String REJECTED = "REJECTED";
    public static final String NEEDS_EVIDENCE = "NEEDS_EVIDENCE";

    public RegressionReview {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(candidateId, "candidateId");
        DatasetVersion.requireText(reviewer, "reviewer");
        DatasetVersion.requireText(verdict, "verdict");
        DatasetVersion.requireText(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!ACCEPTED_FOR_CANDIDATE.equals(verdict) && !REJECTED.equals(verdict)
                && !NEEDS_EVIDENCE.equals(verdict)) {
            throw new IllegalArgumentException("审核结论非法: " + verdict);
        }
    }
}
