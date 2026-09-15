package com.objwww.pr.control.alert.domain.mutation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * mutation 域操作（PB-B1，设计基线 §1/§2.8/§2.11）：一次 R2/R3 副作用的持久执行
 * 身份——由意图（{@link ActionIntent}，同一 digest 锚）在消费模板中铸出；
 * Phase B 全量 {@code dryRun=true}（真实资源零 mutation，A10）。
 *
 * <p>资源定位边界：{@code resourceUid} 只接受 Resource Resolver 权威身份（B2），
 * 未解析 = null = 不得进入派发（B 组不变量：告警标签零授权效力）。
 * {@code resourceEpoch} = 该资源上第几波 mutation（§2.9，与 run lease_epoch 无关）。
 */
public record RcaOperation(
        UUID operationId,
        UUID intentId,
        UUID runId,
        UUID taskId,
        String actionId,
        String actionDigest,
        String resourceUid,
        long resourceEpoch,
        OperationStatus status,
        boolean dryRun,
        String paramsJson,
        Instant createdAt,
        Instant preparedAt,
        Instant dispatchedAt,
        Instant ackAt,
        Instant verifiedAt,
        Instant completedAt) {

    public RcaOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(runId, "runId");
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId 不得为空");
        }
        if (actionDigest == null || actionDigest.length() != 64) {
            throw new IllegalArgumentException("actionDigest 必须为 64 位摘要");
        }
        if (resourceEpoch < 0) {
            throw new IllegalArgumentException("resourceEpoch 不得为负");
        }
        Objects.requireNonNull(status, "status");
        // PD-D1：A10 域闸退役——dry_run=false 不再构造即拒；范围纪律移交
        // mutation_unlock_registry 三元匹配 + 人工审批前置（消费模板裁决）
        Objects.requireNonNull(paramsJson, "paramsJson");
        // 时间面一致性：非 PREPARED 必有 prepared_at；派发后各时点单调（弱校验在状态机）
        if (status != OperationStatus.PREPARED && preparedAt == null) {
            throw new IllegalArgumentException("非 PREPARED 态必须携带 preparedAt");
        }
    }

    /** 消费模板（B4 §2.8）内 PREPARED 铸造形态：锁与 grant 校验同事务完成后调用 */
    public static RcaOperation prepare(UUID operationId, UUID intentId, UUID runId, UUID taskId,
            String actionId, String actionDigest, String resourceUid, long resourceEpoch,
            String paramsJson, Instant now) {
        return new RcaOperation(operationId, intentId, runId, taskId, actionId, actionDigest,
                resourceUid, resourceEpoch, OperationStatus.PREPARED, true, paramsJson,
                now, now, null, null, null, null);
    }

    /** PD-D1：注册表三元解锁 + 人工审批消费后的真执行铸造（scoped mutation） */
    public static RcaOperation prepareReal(UUID operationId, UUID intentId, UUID runId,
            UUID taskId, String actionId, String actionDigest, String resourceUid,
            long resourceEpoch, String paramsJson, Instant now) {
        return new RcaOperation(operationId, intentId, runId, taskId, actionId, actionDigest,
                resourceUid, resourceEpoch, OperationStatus.PREPARED, false, paramsJson,
                now, now, null, null, null, null);
    }

    /** 状态推进（调用方先过 {@link OperationStateMachine#checkTransition}） */
    public RcaOperation withStatus(OperationStatus next, Instant at) {
        OperationStateMachine.checkTransition(this.status, next);
        return new RcaOperation(operationId, intentId, runId, taskId, actionId, actionDigest,
                resourceUid, resourceEpoch, next, dryRun, paramsJson, createdAt,
                preparedAt != null ? preparedAt : at,
                next == OperationStatus.DISPATCHED ? at : dispatchedAt,
                next == OperationStatus.ACKNOWLEDGED ? at : ackAt,
                next == OperationStatus.VERIFIED ? at : verifiedAt,
                next == OperationStatus.COMPLETED ? at : completedAt);
    }
}
