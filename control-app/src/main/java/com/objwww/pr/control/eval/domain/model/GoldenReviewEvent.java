package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GoldenReviewEvent（M5-03）：append-only 复核事件。idempotencyKey 全局唯一
 * （V22 uq_golden_review_event_idem）——同键重放 = 幂等空操作；expectedRevision
 * 记录发起方所见版本（CAS 审计面）；payload 记录迁移目标等审计摘要。
 */
public record GoldenReviewEvent(UUID id,
                                UUID candidateId,
                                GoldenReviewAction action,
                                String actor,
                                long expectedRevision,
                                String idempotencyKey,
                                Map<String, Object> payload,
                                Instant createdAt) {

    public GoldenReviewEvent {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(candidateId, "candidateId 不得为 null");
        Objects.requireNonNull(action, "action 不得为 null");
        DatasetVersion.requireText(actor, "actor");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不得为负");
        }
        DatasetVersion.requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payload, "payload 不得为 null");
        payload = Map.copyOf(payload);
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
    }
}
