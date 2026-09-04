package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * alert_inbox 行（收件箱 + 租约 + 退避 + 投影期 decision；V7 全列对齐）。
 */
public record AlertInbox(
        UUID id,
        AlertGroupEnvelope envelope,
        InboxState state,
        InboxDecision decision,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        int attemptCount,
        int maxAttempts,
        Instant nextRetryAt,
        String lastError,
        Instant receivedAt,
        Instant updatedAt,
        Instant processedAt
) {
    public AlertInbox {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (attemptCount < 0 || maxAttempts <= 0 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attempt 区间非法: " + attemptCount + "/" + maxAttempts);
        }
    }
}
