package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * RCA 模型调用上下文（R7a-1，v2.1 §二.2）：逐层透传禁 ThreadLocal（平台
 * ModelCallContext 同律）。区分逻辑动作（actionSeq=主任务决策步序）与物理请求
 * （physicalSeq 由账本行表达，每个物理重试单独预留单独计费）。
 *
 * <p>leaseHeartbeat 为实施增补（平台上下文 v1.4 同款偏差）：退避等待期的活性切片
 * 需要租约探针，无它网关退避不可取消。deadline=min(run, task, gateway) 的 min 面
 * 在适配器内合并，两侧 deadline 都随上下文携带。
 */
public record RcaModelCallContext(
        UUID runId,
        UUID taskId,
        UUID attemptId,
        long actionSeq,
        int roundId,
        String roleId,
        String roleVersion,
        String roleDigest,
        long leaseEpoch,
        Long configEpoch,
        String releaseDigest,
        String inputSnapshotDigest,
        UUID budgetReservationId,
        Instant runDeadline,
        Instant taskDeadline,
        BooleanSupplier leaseHeartbeat) {

    public RcaModelCallContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        if (actionSeq < 0) {
            throw new IllegalArgumentException("actionSeq 不得为负");
        }
        if (roundId < 0) {
            throw new IllegalArgumentException("roundId 不得为负");
        }
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(roleVersion, "roleVersion");
        Objects.requireNonNull(roleDigest, "roleDigest");
        Objects.requireNonNull(runDeadline, "runDeadline");
        Objects.requireNonNull(taskDeadline, "taskDeadline");
        Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat");
    }

    /** deadline=min(run, task)——网关侧总 deadline 再与配置面取 min（§二.2） */
    public Instant effectiveDeadline() {
        return runDeadline.isBefore(taskDeadline) ? runDeadline : taskDeadline;
    }
}
