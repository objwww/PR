package com.objwww.pr.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * OutboxCommand 共享模型：Control 铸造（T2 同事务 INSERT）、Publisher 消费（T3）共用的命令契约。
 * 纯数据 record，与 V1 outbox_command 表逐字段对齐；lease/attempt/reconcile/remote_id
 * 等执行期字段不属于契约本体，由 Publisher 侧在读模型上补充。
 *
 * <p>关键不变量：publication_epoch 与 aggregate_sequence 在 T2 内于同一行锁下从
 * pr_subject 原子取出（v2.2 §3-3），命令不可能带过期 epoch 出生。
 */
public record OutboxCommand(
        OperationId operationId,
        UUID prSubjectId,
        UUID reviewRunId,
        UUID prRevisionId,
        String aggregateKey,
        long aggregateSequence,
        long publicationEpoch,
        FenceMode fenceMode,
        CommandType commandType,
        OutboxState state,
        String policyVersion,
        Digest payloadArtifactDigest,
        Digest payloadHash,
        RemoteIdentityType remoteIdentityType) {

    public OutboxCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(prSubjectId, "prSubjectId");
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(prRevisionId, "prRevisionId");
        Objects.requireNonNull(aggregateKey, "aggregateKey");
        Objects.requireNonNull(fenceMode, "fenceMode");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(remoteIdentityType, "remoteIdentityType");
        if (aggregateSequence < 1) {
            // V1: pr_subject.next_outbox_sequence 从 1 起
            throw new IllegalArgumentException("aggregateSequence 必须 >= 1: " + aggregateSequence);
        }
        if (publicationEpoch < 0) {
            throw new IllegalArgumentException("publicationEpoch 必须 >= 0: " + publicationEpoch);
        }
    }
}
