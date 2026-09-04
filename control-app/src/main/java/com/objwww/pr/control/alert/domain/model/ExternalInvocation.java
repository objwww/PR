package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 外部调用账本条目（V5 账本同形态；只记账不决策）。
 *
 * <p>调用前 insertStarted（写失败=零触网）；终态 SUCCEEDED/FAILED/UNKNOWN；
 * 崩溃回收把悬挂 STARTED 标 UNKNOWN（可对账不可重放）。
 */
public record ExternalInvocation(
        UUID id,
        UUID invocationId,
        int callSeq,
        UUID runId,
        UUID taskId,
        UUID attemptId,
        long leaseEpoch,
        String endpoint,
        Digest requestDigest,
        Digest responseDigest,
        ExternalInvocationState state,
        Integer httpStatus,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        boolean usageMissing,
        String holmesVersion,
        String model,
        String toolsetVersion,
        String errorClass,
        String sanitizedMessage,
        Instant startedAt,
        Instant finishedAt
) {
    public ExternalInvocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        if (callSeq < 1) {
            throw new IllegalArgumentException("callSeq 从 1 起");
        }
    }
}
