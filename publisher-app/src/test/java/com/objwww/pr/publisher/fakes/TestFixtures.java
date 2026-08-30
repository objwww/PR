package com.objwww.pr.publisher.fakes;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FenceMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.RemoteIdentityType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 测试装配公共件 */
public final class TestFixtures {

    public static final Digest PAYLOAD_HASH = new Digest("a".repeat(64));
    public static final UUID SUBJECT_ID = UUID.randomUUID();
    public static final UUID RUN_ID = UUID.randomUUID();
    public static final UUID REVISION_ID = UUID.randomUUID();

    private TestFixtures() {
    }

    public static ClaimedCommand command(CommandType type, long sequence, long epoch,
                                         OutboxState state, int attemptCount, int maxAttempts) {
        OperationId opId = OperationId.random();
        // 每条命令独立 payloadHash（同 digest 即同内容的 CAS 语义在测试里同样成立）
        Digest payloadHash = Digest.sha256Of("payload:" + opId);
        return new ClaimedCommand(opId, SUBJECT_ID, RUN_ID, REVISION_ID,
                "pr:1#1", sequence, epoch, FenceMode.CURRENT_EPOCH, type, state,
                "m0-policy-v1", null, payloadHash, remoteIdentityOf(type),
                1L, attemptCount, maxAttempts, 0);
    }

    public static ClaimedCommand withState(ClaimedCommand c, OutboxState state) {
        return new ClaimedCommand(c.operationId(), c.prSubjectId(), c.reviewRunId(), c.prRevisionId(),
                c.aggregateKey(), c.aggregateSequence(), c.publicationEpoch(), c.fenceMode(),
                c.commandType(), state, c.policyVersion(), c.payloadArtifactDigest(), c.payloadHash(),
                c.remoteIdentityType(), c.leaseEpoch(), c.attemptCount(), c.maxAttempts(),
                c.reconcileNotFoundCount());
    }

    public static ClaimedCommand withAttempts(ClaimedCommand c, int attemptCount) {
        return new ClaimedCommand(c.operationId(), c.prSubjectId(), c.reviewRunId(), c.prRevisionId(),
                c.aggregateKey(), c.aggregateSequence(), c.publicationEpoch(), c.fenceMode(),
                c.commandType(), c.state(), c.policyVersion(), c.payloadArtifactDigest(), c.payloadHash(),
                c.remoteIdentityType(), c.leaseEpoch(), attemptCount, c.maxAttempts(),
                c.reconcileNotFoundCount());
    }

    private static RemoteIdentityType remoteIdentityOf(CommandType type) {
        return switch (type) {
            case CREATE_CHECK -> RemoteIdentityType.EXTERNAL_ID;
            case UPDATE_CHECK -> RemoteIdentityType.CHECK_RUN_ID;
            case PUBLISH_REVIEW -> RemoteIdentityType.REVIEW_MARKER;
        };
    }

    /** 与 control ReviewOrchestrator.buildCheckPayload 同构 */
    public static Map<String, Object> checkPayload(ClaimedCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", command.operationId().toString());
        payload.put("repo", "octo/demo");
        payload.put("head_sha", "0123456789abcdef0123456789abcdef01234567");
        payload.put("name", "ai-code-review");
        payload.put("finding_count", 2);
        return payload;
    }

    /** 与 control ReviewOrchestrator.buildReviewPayload 同构 */
    public static Map<String, Object> reviewPayload(ClaimedCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", command.operationId().toString());
        payload.put("repo", "octo/demo");
        payload.put("pr_number", 42);
        payload.put("commit_id", "0123456789abcdef0123456789abcdef01234567");
        payload.put("marker", com.objwww.pr.publisher.domain.handler.PublishReviewHandler
                .markerOf(command.operationId()));
        payload.put("findings", java.util.List.of(Map.of(
                "file", "src/A.java", "line_start", 7, "severity", "HIGH", "message", "空指针风险")));
        return payload;
    }

    public static Map<String, Object> updatePayload(ClaimedCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", command.operationId().toString());
        payload.put("repo", "octo/demo");
        payload.put("check_run_id", "998877");
        payload.put("conclusion", "neutral");
        return payload;
    }
}
