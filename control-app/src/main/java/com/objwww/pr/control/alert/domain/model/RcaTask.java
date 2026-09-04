package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 调度单元（V1 work_item 形态 + SLA 晋升列，§6.2）。
 *
 * <p>deadlineAt = readySince + sla(priority)；critical 用 {@link Instant#MAX}（映射 PG 'infinity'，永不到期）。
 * readySince 在重试置 READY 时刷新——退避结束不插队。
 */
public record RcaTask(
        UUID id,
        UUID runId,
        String taskKey,
        RcaTaskState state,
        int priority,
        Instant availableAt,
        Instant readySince,
        Instant deadlineAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        int attemptCount,
        int maxAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    /** 本期唯一 task_key（V7 注释对齐；多 Agent 属 AM2+） */
    public static final String HOLMES_INVESTIGATE = "HOLMES_INVESTIGATE";

    public RcaTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskKey, "taskKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(readySince, "readySince");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (priority < 0 || attemptCount < 0 || maxAttempts <= 0 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("priority/attempt 区间非法");
        }
    }
}
