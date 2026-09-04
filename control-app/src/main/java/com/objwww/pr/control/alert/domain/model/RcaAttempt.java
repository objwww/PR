package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 物理执行尝试（V1 step_attempt 同构）：epoch 栅栏 + 终态记录。
 */
public record RcaAttempt(
        UUID id,
        UUID taskId,
        int attemptNo,
        long leaseEpoch,
        String workerId,
        RcaAttemptStatus status,
        String errorClass,
        String errorCode,
        String errorDetail,
        Instant startedAt,
        Instant finishedAt
) {
    public RcaAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 从 1 起");
        }
    }
}
