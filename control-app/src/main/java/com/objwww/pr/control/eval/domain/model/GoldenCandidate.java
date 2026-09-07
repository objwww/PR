package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GoldenCandidate（M5-03，INV-AM5-2）：GT 提案工作流实体。
 * DRAFT→REVIEW→PUBLISHED/REJECTED/WITHDRAWN（{@code GoldenCandidateStateMachine}
 * 矩阵唯一权威）；发布/拒绝必须双签且同人不能双签（V22 ck_golden_dual_review DB
 * 兜底）；revision 为乐观锁版本（CAS 迁移），PUBLISHED 发布 = case_version 追加
 * 新行（insert-only 不破）。
 */
public record GoldenCandidate(UUID id,
                              UUID caseVersionId,
                              Map<String, Object> proposedGt,
                              String reason,
                              GoldenCandidateState state,
                              String proposedBy,
                              String reviewerA,
                              String reviewerB,
                              long revision,
                              Instant createdAt,
                              Instant updatedAt) {

    public GoldenCandidate {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(caseVersionId, "caseVersionId 不得为 null");
        Objects.requireNonNull(proposedGt, "proposedGt 不得为 null");
        if (proposedGt.isEmpty()) {
            throw new IllegalArgumentException("proposedGt 不得为空（提案必须携带 GT 内容）");
        }
        proposedGt = Map.copyOf(proposedGt);
        DatasetVersion.requireText(reason, "reason");
        Objects.requireNonNull(state, "state 不得为 null");
        DatasetVersion.requireText(proposedBy, "proposedBy");
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不得为负");
        }
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
        Objects.requireNonNull(updatedAt, "updatedAt 不得为 null");
    }
}
