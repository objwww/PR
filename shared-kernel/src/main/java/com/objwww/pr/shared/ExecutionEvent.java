package com.objwww.pr.shared;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行账本事件（execution_event，只追加，I9）。
 * Control 与 Publisher 都会写账本，事件契约放 shared-kernel。
 * position / recorded_at 由 DB 生成（identity 主键 / default now()），不在本 record 内。
 */
public record ExecutionEvent(
        UUID eventId,
        UUID reviewRunId,
        UUID prRevisionId,
        UUID stepId,
        UUID attemptId,
        ExecutionEventType eventType,
        int schemaVersion,
        UUID causationEventId,
        UUID correlationId,
        String producer,
        Map<String, Object> payload,
        Instant occurredAt) {

    public ExecutionEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(prRevisionId, "prRevisionId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (schemaVersion <= 0) {
            // 对齐 V1 ck_event_schema_version
            throw new IllegalArgumentException("schemaVersion 必须 > 0: " + schemaVersion);
        }
        payload = Map.copyOf(payload);
    }
}
