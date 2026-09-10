package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * EV-04 评测发起/取消持久化命令（V81 eval_run_command；V27 OperatorCommand 同构：
 * 先持久化再生效——命令行是审计真相源，HTTP 线程不跑批）。
 *
 * <p>状态单向推进：PENDING（已持久化待领取）→ CLAIMED（worker 已领）→
 * DONE / FAILED（终态）；REJECTED = 服务层显式拒绝留痕。幂等锚
 * (command_type, idempotency_key)：同键重放返回原命令行。
 *
 * <p>LAUNCH 的 evalRunId 是预定 run 身份：worker 以该 id insertRunning，
 * 崩溃重放同键不会产生第二个 run（EU15 稳定身份面）。
 */
public record EvalRunCommand(UUID id,
                             Type commandType,
                             UUID evalRunId,
                             String idempotencyKey,
                             String payloadJson,
                             String payloadHash,
                             State state,
                             String actor,
                             String workerId,
                             Instant createdAt,
                             Instant claimedAt,
                             Instant finishedAt) {

    public enum Type {LAUNCH, CANCEL}

    public enum State {PENDING, CLAIMED, DONE, FAILED, REJECTED}

    public EvalRunCommand {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(commandType, "command_type 不得为 null");
        Objects.requireNonNull(evalRunId, "eval_run_id 不得为 null");
        Objects.requireNonNull(state, "state 不得为 null");
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotency_key 必填且 ≤128 字符");
        }
        if (payloadHash == null || payloadHash.length() != 64) {
            throw new IllegalArgumentException("payload_hash 必为 64 位 hex");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor 必填");
        }
        Objects.requireNonNull(createdAt, "created_at 不得为 null");
    }

    /** 新提交形（PENDING，无 worker/领取/完成时刻） */
    public static EvalRunCommand pending(UUID id, Type type, UUID evalRunId,
                                         String idempotencyKey, String payloadJson,
                                         String payloadHash, String actor, Instant createdAt) {
        return new EvalRunCommand(id, type, evalRunId, idempotencyKey, payloadJson,
                payloadHash, State.PENDING, actor, null, createdAt, null, null);
    }
}
