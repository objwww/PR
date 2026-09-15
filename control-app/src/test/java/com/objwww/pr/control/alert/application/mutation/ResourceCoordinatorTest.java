package com.objwww.pr.control.alert.application.mutation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resource Coordinator 单测（PB-B3，§2.9，fake 存储）：BUSY 显式拒绝不排队、
 * 代数单调、TTL 孤儿化只标记不让渡、非法 TTL 拒绝装配。
 */
class ResourceCoordinatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-15T17:00:00Z"),
            ZoneOffset.UTC);

    static final class FakeLockStore implements ResourceLockStore {
        record Lock(String uid, UUID opId, UUID runId, long epoch, String state,
                Instant ttlUntil) {
        }
        final Map<String, Lock> locks = new HashMap<>();
        final Map<String, Long> counters = new HashMap<>();
        final List<String> releaseAttempts = new ArrayList<>();
        boolean terminalOperationStatus;

        @Override
        public Acquire acquire(String uid, UUID opId, UUID runId, Duration ttl, Instant now) {
            if (locks.containsKey(uid)) {
                return Acquire.busy(uid);
            }
            long epoch = counters.merge(uid, 1L, Long::sum);
            locks.put(uid, new Lock(uid, opId, runId, epoch, "HELD", now.plus(ttl)));
            return new Acquire(uid, true, epoch);
        }

        @Override
        public boolean releaseOnTerminalState(String uid, UUID opId) {
            releaseAttempts.add(uid + ":" + opId);
            Lock lock = locks.get(uid);
            if (lock == null || !lock.opId().equals(opId) || !terminalOperationStatus) {
                return false;
            }
            locks.remove(uid);
            return true;
        }

        @Override
        public int markOrphanedExpired(Instant now) {
            int n = 0;
            for (Map.Entry<String, Lock> entry : locks.entrySet()) {
                Lock l = entry.getValue();
                if ("HELD".equals(l.state()) && l.ttlUntil().isBefore(now)) {
                    locks.put(entry.getKey(), new Lock(l.uid(), l.opId(), l.runId(),
                            l.epoch(), "ORPHANED", l.ttlUntil()));
                    n++;
                }
            }
            return n;
        }

        @Override
        public List<OrphanedLock> orphanedLocks() {
            return locks.values().stream().filter(l -> "ORPHANED".equals(l.state()))
                    .map(l -> new OrphanedLock(l.uid(), l.opId(), l.runId(), l.epoch(),
                            l.ttlUntil()))
                    .toList();
        }

        @Override
        public Optional<LockView> find(String uid) {
            Lock l = locks.get(uid);
            return l == null ? Optional.empty() : Optional.of(new LockView(l.uid(), l.opId(),
                    l.runId(), l.epoch(), l.state()));
        }
    }

    @Test
    void pbC01_领锁后BUSY_显式拒绝_代数单调() {
        FakeLockStore store = new FakeLockStore();
        var coordinator = new ResourceCoordinator(store, Duration.ofMinutes(10), FIXED);
        UUID opA = UUID.randomUUID();
        UUID opB = UUID.randomUUID();
        UUID runA = UUID.randomUUID();

        var first = coordinator.acquire("res://demo/checkout", opA, runA);
        assertThat(first.acquired()).isTrue();
        assertThat(first.resourceEpoch()).isEqualTo(1);

        var second = coordinator.acquire("res://demo/checkout", opB, UUID.randomUUID());
        assertThat(second.acquired()).isFalse(); // BUSY 显式结果——不排队不覆盖
        assertThat(second.resourceEpoch()).isEqualTo(-1);

        // 释放后再次领锁：代数自 counter 单调递增（锁删行不丢代数）
        store.terminalOperationStatus = true;
        assertThat(coordinator.releaseOnTerminalState("res://demo/checkout", opA)).isTrue();
        var third = coordinator.acquire("res://demo/checkout", opB, UUID.randomUUID());
        assertThat(third.acquired()).isTrue();
        assertThat(third.resourceEpoch()).isEqualTo(2);
    }

    @Test
    void pbC02_释放状态闸_UNKNOWN拒_终态放() {
        FakeLockStore store = new FakeLockStore();
        var coordinator = new ResourceCoordinator(store, Duration.ofMinutes(10), FIXED);
        UUID op = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        coordinator.acquire("res://demo/orders-db", op, run);

        // Operation UNKNOWN（未到释放集）→ 拒绝释放，锁保持
        store.terminalOperationStatus = false;
        assertThat(coordinator.releaseOnTerminalState("res://demo/orders-db", op)).isFalse();
        assertThat(store.find("res://demo/orders-db")).isPresent(); // 锁仍在 = BUSY

        // Operation → COMPLETED（释放集）→ 释放
        store.terminalOperationStatus = true;
        assertThat(coordinator.releaseOnTerminalState("res://demo/orders-db", op)).isTrue();
        assertThat(store.find("res://demo/orders-db")).isEmpty();
    }

    @Test
    void pbC03_TTL孤儿化_只标记不让渡() {
        FakeLockStore store = new FakeLockStore();
        var coordinator = new ResourceCoordinator(store, Duration.ofMinutes(10), FIXED);
        UUID op = UUID.randomUUID();
        coordinator.acquire("res://demo/frontend", op, UUID.randomUUID());

        // TTL 未到：不孤儿化
        assertThat(coordinator.markOrphanedExpired()).isZero();
        // TTL 已过：HELD → ORPHANED（锁行保留——资源持续 BUSY）
        Instant afterTtl = FIXED.instant().plus(Duration.ofMinutes(11));
        assertThat(store.markOrphanedExpired(afterTtl)).isEqualTo(1);
        assertThat(store.orphanedLocks()).hasSize(1);
        assertThat(store.orphanedLocks().get(0).operationId()).isEqualTo(op);
        // ORPHANED 期间新 mutation 领锁 = BUSY（不让渡）
        var next = coordinator.acquire("res://demo/frontend", UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(next.acquired()).isFalse();
    }

    @Test
    void pbC04_非法TTL_装配即拒() {
        assertThatThrownBy(() -> new ResourceCoordinator(new FakeLockStore(), Duration.ZERO,
                FIXED)).isInstanceOf(IllegalArgumentException.class);
    }
}
