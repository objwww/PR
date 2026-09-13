package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 回归案例候选（OP-01，V104 rca_regression_candidate）：从已终态报告（可挂
 * 反馈）提出的入集候选——sourceDigest 冻结来源指纹（源报告后续修订不静默改
 * 候选，FO28）；正式入集 = 独立审核 ACCEPTED 后 materialize 写不可变
 * DatasetVersion/CaseVersion。状态机：PENDING_REVIEW → ACCEPTED/REJECTED/
 * NEEDS_EVIDENCE；两名审核者结论相异 → DISPUTED（裁决/待补证，FO27——不采用
 * 最后写入自动胜出）。反馈不能自动发布 Prompt/Skill（§5.2）。
 */
public record RegressionCandidate(
        UUID id,
        UUID sourceRunId,
        UUID sourceReportId,
        UUID sourceFeedbackId,
        String sourceDigest,
        String caseKey,
        String scenarioFamilyId,
        String state,
        String createdBy,
        Instant createdAt,
        String reviewedBy,
        String reviewReason,
        Instant reviewedAt) {

    public static final String ST_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String ST_ACCEPTED = "ACCEPTED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_NEEDS_EVIDENCE = "NEEDS_EVIDENCE";
    public static final String ST_DISPUTED = "DISPUTED";

    public RegressionCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceRunId, "sourceRunId");
        Objects.requireNonNull(sourceReportId, "sourceReportId");
        Objects.requireNonNull(sourceDigest, "sourceDigest");
        DatasetVersion.requireText(caseKey, "caseKey");
        DatasetVersion.requireText(scenarioFamilyId, "scenarioFamilyId");
        DatasetVersion.requireText(state, "state");
        DatasetVersion.requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        boolean pending = ST_PENDING_REVIEW.equals(state);
        if (pending != (reviewedBy == null && reviewedAt == null)) {
            throw new IllegalArgumentException(
                    "PENDING_REVIEW 无审核面，其余状态必带审核人/时间: state=" + state);
        }
    }

    public boolean pending() {
        return ST_PENDING_REVIEW.equals(state);
    }

    /** 审核推进副本（首个审核定状态；后续相异结论由服务层转 DISPUTED） */
    public RegressionCandidate withReviewed(String newState, String reviewer,
            String reason, Instant at) {
        return new RegressionCandidate(id, sourceRunId, sourceReportId,
                sourceFeedbackId, sourceDigest, caseKey, scenarioFamilyId, newState,
                createdBy, createdAt, reviewer, reason, at);
    }
}
