package com.objwww.pr.publisher.domain.model;

import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FenceMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.RemoteIdentityType;

import java.util.Objects;
import java.util.UUID;

/**
 * Publisher 侧的 outbox_command 读模型：在 shared 的 {@link com.objwww.pr.shared.OutboxCommand}
 * 契约字段之上补充执行期字段（lease/attempt/reconcile）。只读视图——Publisher 对 outbox
 * 只有 SELECT/UPDATE（I10 的另一面），不存在 INSERT 路径。
 */
public record ClaimedCommand(
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
        RemoteIdentityType remoteIdentityType,
        long leaseEpoch,
        int attemptCount,
        int maxAttempts,
        int reconcileNotFoundCount) {

    public ClaimedCommand {
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
    }
}
