package com.objwww.pr.control.alert.application.mutation;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Resource Coordinator（PB-B3，设计基线 §2.9）：资源 mutation 锁的裁决门面——
 * B4 消费模板在 PREPARED 同事务先领锁（acquire 失利 = 整个消费事务回滚），
 * reconcile/收尾面走状态闸释放；TTL 到期由孤儿化扫描转为 reconcile 驱动。
 * 本类不做任何"等待重试"——BUSY 是显式结果（宁可串行不可并发 mutation）。
 */
public class ResourceCoordinator {

    private final ResourceLockStore store;
    private final Duration defaultTtl;
    private final Clock clock;

    public ResourceCoordinator(ResourceLockStore store, Duration defaultTtl, Clock clock) {
        this.store = Objects.requireNonNull(store);
        if (defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("锁 TTL 必须为正");
        }
        this.defaultTtl = defaultTtl;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 领锁（B4 消费模板内调用；BUSY = 显式拒绝，不排队不覆盖） */
    public ResourceLockStore.Acquire acquire(String resourceUid, java.util.UUID operationId,
            java.util.UUID runId) {
        return store.acquire(resourceUid, operationId, runId, defaultTtl, clock.instant());
    }

    /** 状态闸释放（Operation ∈ 释放集才生效；UNKNOWN/ESCALATED 拒绝） */
    public boolean releaseOnTerminalState(String resourceUid, java.util.UUID operationId) {
        return store.releaseOnTerminalState(resourceUid, operationId);
    }

    /** TTL 孤儿化扫描（调度循环驱动；返回孤儿化数量，明细走 orphanedLockList） */
    public int markOrphanedExpired() {
        return store.markOrphanedExpired(clock.instant());
    }

    public java.util.List<ResourceLockStore.OrphanedLock> orphanedLockList() {
        return store.orphanedLocks();
    }

    public java.util.Optional<ResourceLockStore.LockView> find(String resourceUid) {
        return store.find(resourceUid);
    }
}
