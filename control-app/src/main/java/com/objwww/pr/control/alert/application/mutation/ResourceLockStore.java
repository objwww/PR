package com.objwww.pr.control.alert.application.mutation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 资源 mutation 锁存储端口（PB-B3，V116，§2.9）：
 *
 * <ul>
 *   <li>{@link #acquire} —— 行存在（含 ORPHANED）即 BUSY：孤儿锁<b>不让渡</b>
 *       （B 组不变量）；代数自 {@code resource_mutation_counter} 单调领取；</li>
 *   <li>{@link #releaseOnTerminalState} —— 释放 SQL 闸绑定 Operation 状态 ∈
 *       {VERIFIED, COMPLETED, FAILED_CONFIRMED, CANCELLED_BEFORE_DISPATCH}；
 *       UNKNOWN/RECONCILING/ESCALATED 拒绝释放（side-effect zombie 关闭）；</li>
 *   <li>{@link #markOrphanedExpired} —— TTL 唯一职责：HELD 且过期 → ORPHANED
 *       （持有者孤儿化，驱动 reconcile），锁行保留资源持续 BUSY。</li>
 * </ul>
 *
 * <p>所有方法 join 调用方事务——acquire 是 B4 消费模板的组成部分（锁与
 * PREPARED/outbox/事件同 COMMIT，§2.8）。
 */
public interface ResourceLockStore {

    /** 领锁结果：ACQUIRED（携带本次 mutation 的 resource_epoch）或 BUSY */
    record Acquire(String resourceUid, boolean acquired, long resourceEpoch) {
        public static Acquire busy(String resourceUid) {
            return new Acquire(resourceUid, false, -1);
        }
    }

    Acquire acquire(String resourceUid, UUID operationId, UUID runId, java.time.Duration ttl,
            Instant now);

    /**
     * 状态闸释放：Operation 状态 ∈ 释放集才删锁行。返回 false = 状态未到（锁保持）。
     */
    boolean releaseOnTerminalState(String resourceUid, UUID operationId);

    /** TTL 孤儿化扫描：HELD 且过期 → ORPHANED；返回孤儿化行数 */
    int markOrphanedExpired(Instant now);

    /** 孤儿锁视图（reconcile 驱动面） */
    record OrphanedLock(String resourceUid, UUID operationId, UUID runId, long resourceEpoch,
            java.time.Instant ttlExpiredAt) {
    }

    List<OrphanedLock> orphanedLocks();

    Optional<LockView> find(String resourceUid);

    record LockView(String resourceUid, UUID operationId, UUID runId, long resourceEpoch,
            String state) {
    }
}
