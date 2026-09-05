package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.CompensationEvent;
import com.objwww.pr.arena.domain.model.OutboxState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 补偿 outbox 仓储端口（M2-05/12/13）。领取 = FOR UPDATE SKIP LOCKED + CAS，
 * 租约 + epoch 栅栏；payload 为反向回补计划 JSON。
 */
public interface CompensationOutboxRepository {

    /** 业务事务内插入 PENDING 行（M2-12：与业务终态同生共死） */
    void insertPending(CompensationEvent event);

    /** 可领行数（worker 空转判定） */
    long countClaimable();

    /** 领取：SKIP LOCKED claim → CAS PENDING/RETRY_WAIT→CLAIMED(epoch+1, 租约) */
    record Claimed(CompensationEvent event, String owner, long leaseEpoch) {
    }

    Optional<Claimed> claimNext(String owner, Duration lease);

    /** 租约过期的在途行回收 → PENDING（epoch+1），返回回收行数 */
    int reapExpiredLeases();

    /** CAS 迁移（epoch 栅栏：state/from/epoch 三元匹配才生效） */
    boolean casState(UUID id, OutboxState from, OutboxState to, long leaseEpoch);

    /** 终态收口（SUCCEEDED/SKIPPED/DEAD/CANCELLED：清租约 + finished_at） */
    boolean casTerminal(UUID id, OutboxState from, long leaseEpoch);

    /** 退避重试：attempt_count+1 + available_at 推后；耗尽 → DEAD 由调用方判定 */
    boolean casRetry(UUID id, long leaseEpoch, Duration backoff, int maxAttempts);

    List<CompensationEvent> findByOrder(UUID orderId);
}
