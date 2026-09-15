package com.objwww.pr.control.alert.application.mutation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 派发指令 outbox 端口（PB-B4，V117 operation_outbox）：
 *
 * <ul>
 *   <li>{@link #insert} —— 与 PREPARED 同事务（§2.8 五步模板之内）；</li>
 *   <li>{@link #claimNext} —— SKIP LOCKED + 租约 CAS 领取（at-least-once；
 *       租约过期行可被重领）；</li>
 *   <li>{@link #reclaimExpiredLeases} —— 崩溃回收：租约过期的 CLAIMED 回 PENDING；</li>
 *   <li>{@link #markDispatched} / {@link #backToPending} —— 终态化与 RETRYABLE
 *       重派（B5 reconcile：1:1 行回 PENDING，uq 兼容）。</li>
 * </ul>
 */
public interface OperationOutboxStore {

    void insert(UUID outboxId, UUID operationId, Instant now);

    record Claimed(UUID outboxId, UUID operationId, UUID runId, long leaseEpoch,
            int attemptCount) {
    }

    Optional<Claimed> claimNext(String owner, java.time.Duration lease, Instant now);

    int reclaimExpiredLeases(Instant now);

    boolean markDispatched(UUID outboxId, Instant now);

    boolean backToPending(UUID operationId, Instant now);

    Optional<OutboxView> findByOperation(UUID operationId);

    record OutboxView(UUID outboxId, UUID operationId, String state, int attemptCount,
            long leaseEpoch) {
    }
}
