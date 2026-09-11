package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 调度单元（V1 work_item 形态 + SLA 晋升列，§6.2）。
 *
 * <p>deadlineAt = readySince + sla(priority)；critical 用 {@link Instant#MAX}（映射 PG 'infinity'，永不到期）。
 * readySince 在重试置 READY 时刷新——退避结束不插队。
 *
 * <p>R7-X1（V46，v2.1 §四）：roundId = 主 Agent 委派/补证 round 身份（初始 round0，
 * 首期 max_delegation_batches=2），taskKey 回归纯业务实例标识——唯一键
 * (run_id, round_id, task_key)。角色身份不在本记录——见 rca_task_execution_binding
 * （TaskExecutionBinding）。
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
        Instant updatedAt,
        int roundId
) {
    /** Holmes 主路径调查 task（V7 注释对齐；M6-01 起与 NATIVE 并存，按路由引擎选 key） */
    public static final String HOLMES_INVESTIGATE = "HOLMES_INVESTIGATE";

    /** Native agent 调查驱动 task（M6-01）：worker 领取后由 NativeInvestigationExecutor 驱动 DAG */
    public static final String NATIVE_INVESTIGATE = "NATIVE_INVESTIGATE";

    /** R7-X11：主 Agent 调查 task（主模式 run 的初始唯一图节点） */
    public static final String PRIMARY_INVESTIGATE = "PRIMARY_INVESTIGATE";

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
        if (roundId < 0) {
            throw new IllegalArgumentException("roundId 不得为负: " + roundId);
        }
    }

    /** V46 前形态（round 0）：存量铸造/测试面零改动 */
    public RcaTask(UUID id, UUID runId, String taskKey, RcaTaskState state, int priority,
            Instant availableAt, Instant readySince, Instant deadlineAt, String leaseOwner,
            Instant leaseUntil, long leaseEpoch, int attemptCount, int maxAttempts,
            Instant createdAt, Instant updatedAt) {
        this(id, runId, taskKey, state, priority, availableAt, readySince, deadlineAt,
                leaseOwner, leaseUntil, leaseEpoch, attemptCount, maxAttempts,
                createdAt, updatedAt, 0);
    }

    /** 携带原 roundId 的状态/调度列更新伴生（记录除 round 外全列照抄） */
    public RcaTask withState(RcaTaskState newState, Instant newUpdatedAt) {
        return new RcaTask(id, runId, taskKey, newState, priority, availableAt, readySince,
                deadlineAt, leaseOwner, leaseUntil, leaseEpoch, attemptCount, maxAttempts,
                createdAt, newUpdatedAt, roundId);
    }
}
