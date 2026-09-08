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
    /** Holmes 主路径调查 task（V7 注释对齐；M6-01 起与 NATIVE 并存，按路由引擎选 key） */
    public static final String HOLMES_INVESTIGATE = "HOLMES_INVESTIGATE";

    /** Native agent 调查驱动 task（M6-01）：worker 领取后由 NativeInvestigationExecutor 驱动 DAG */
    public static final String NATIVE_INVESTIGATE = "NATIVE_INVESTIGATE";

    /** 铸造点按路由引擎选 task_key（run 启动固定，引擎决定执行器分派面） */
    public static String taskKeyFor(RcaEngine engine) {
        return engine == RcaEngine.NATIVE ? NATIVE_INVESTIGATE : HOLMES_INVESTIGATE;
    }

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
