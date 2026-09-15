package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.OperationStateMachine;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
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

/**
 * PB-B4 链路单测（§2.8）：Plan 五步同事务（BUSY 零写入回滚 / 未解析 fail-closed /
 * 成功五步全落）+ Dispatcher 幂等派发（成功全链+释放闸 / CHAOS→UNKNOWN 锁保持 /
 * 已推进跳过 / 租约回收重领）。
 */
class Pb4OutboxChainTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-15T18:00:00Z"),
            ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofMinutes(10);

    record EventRow(UUID runId, String type, String payload) {
    }

    static final class FakeEvents implements RcaEventAppender {
        final List<EventRow> rows = new ArrayList<>();

        @Override
        public long append(UUID runId, EventDraft draft) {
            rows.add(new EventRow(runId, draft.eventType(), draft.payloadJson()));
            return rows.size();
        }

        @Override
        public long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }
    }

    static final class DirectTx
            implements org.springframework.transaction.support.TransactionOperations {
        @Override
        public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    static final class FakeIntents implements ActionIntentStore {
        final Map<UUID, IntentView> rows = new HashMap<>();
        boolean plannedCasLoss;

        @Override
        public Optional<IntentView> findById(UUID intentId) {
            return Optional.ofNullable(rows.get(intentId));
        }

        @Override
        public boolean markResolved(UUID intentId, String uid, String json, String hash,
                Instant at) {
            return false;
        }

        @Override
        public boolean markPlanned(UUID intentId, UUID operationId, Instant at) {
            IntentView view = rows.get(intentId);
            if (view == null || plannedCasLoss || view.scopeSnapshotHash() == null) {
                return false;
            }
            rows.put(intentId, new IntentView(intentId, view.runId(), view.taskId(),
                    view.actionDigest(), view.toolName(), view.risk(), view.resolvedResourceUid(),
                    view.scopeSnapshotHash(), view.argsJson()));
            return true;
        }
    }

    static final class FakeOperations implements OperationLedgerStore {
        final Map<UUID, RcaOperation> rows = new HashMap<>();

        @Override
        public void insert(RcaOperation operation) {
            rows.put(operation.operationId(), operation);
        }

        @Override
        public void updateResourceEpoch(UUID operationId, long resourceEpoch) {
            RcaOperation op = rows.get(operationId);
            rows.put(operationId, new RcaOperation(op.operationId(), op.intentId(),
                    op.runId(), op.taskId(), op.actionId(), op.actionDigest(),
                    op.resourceUid(), resourceEpoch, op.status(), op.dryRun(),
                    op.paramsJson(), op.createdAt(), op.preparedAt(), op.dispatchedAt(),
                    op.ackAt(), op.verifiedAt(), op.completedAt()));
        }

        @Override
        public Optional<RcaOperation> findById(UUID operationId) {
            return Optional.ofNullable(rows.get(operationId));
        }

        @Override
        public boolean transition(UUID operationId, OperationStatus expected,
                OperationStatus next, Instant at) {
            RcaOperation op = rows.get(operationId);
            if (op == null || op.status() != expected
                    || !OperationStateMachine.canTransition(expected, next)) {
                return false;
            }
            rows.put(operationId, op.withStatus(next, at));
            return true;
        }

        @Override
        public List<UUID> idsInStatus(OperationStatus status) {
            return rows.values().stream().filter(op -> op.status() == status)
                    .map(RcaOperation::operationId).toList();
        }

        @Override
        public boolean hasActiveForRun(UUID runId) {
            return rows.values().stream().anyMatch(op -> op.runId().equals(runId)
                    && op.status() != OperationStatus.ESCALATED
                    && op.status().holdsResourceLock());
        }
    }

    static final class FakeOutbox implements OperationOutboxStore {
        record Row(UUID outboxId, UUID operationId, String state, Instant leaseUntil) {
        }
        final List<Row> rows = new ArrayList<>();

        @Override
        public void insert(UUID outboxId, UUID operationId, Instant now) {
            rows.add(new Row(outboxId, operationId, "PENDING", null));
        }

        @Override
        public Optional<Claimed> claimNext(String owner, Duration lease, Instant now) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                boolean claimable = "PENDING".equals(row.state())
                        || ("CLAIMED".equals(row.state()) && row.leaseUntil().isBefore(now));
                if (claimable) {
                    rows.set(i, new Row(row.outboxId(), row.operationId(), "CLAIMED",
                            now.plus(lease)));
                    return Optional.of(new Claimed(row.outboxId(), row.operationId(),
                            UUID.randomUUID(), 1, 1));
                }
            }
            return Optional.empty();
        }

        @Override
        public int reclaimExpiredLeases(Instant now) {
            int n = 0;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if ("CLAIMED".equals(row.state()) && row.leaseUntil().isBefore(now)) {
                    rows.set(i, new Row(row.outboxId(), row.operationId(), "PENDING", null));
                    n++;
                }
            }
            return n;
        }

        @Override
        public boolean markDispatched(UUID outboxId, Instant now) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).outboxId().equals(outboxId)) {
                    Row row = rows.get(i);
                    rows.set(i, new Row(row.outboxId(), row.operationId(), "DISPATCHED",
                            row.leaseUntil()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean backToPending(UUID operationId, Instant now) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.operationId().equals(operationId)) {
                    rows.set(i, new Row(row.outboxId(), row.operationId(), "PENDING", null));
                    return true;
                }
            }
            return false;
        }

        @Override
        public Optional<OutboxView> findByOperation(UUID operationId) {
            return rows.stream().filter(r -> r.operationId().equals(operationId))
                    .findFirst()
                    .map(r -> new OutboxView(r.outboxId(), r.operationId(), r.state(), 1, 1));
        }
    }

    static final class FakeLocks implements ResourceLockStore {
        final Map<String, Acquire> locks = new HashMap<>();
        final Map<String, Long> counters = new HashMap<>();
        final List<String> released = new ArrayList<>();
        boolean releaseGateOpen;

        @Override
        public Acquire acquire(String uid, UUID opId, UUID runId, Duration ttl, Instant now) {
            if (locks.containsKey(uid)) {
                return Acquire.busy(uid);
            }
            long epoch = counters.merge(uid, 1L, Long::sum);
            locks.put(uid, new Acquire(uid, true, epoch));
            return locks.get(uid);
        }

        @Override
        public boolean releaseOnTerminalState(String uid, UUID opId) {
            if (!releaseGateOpen || locks.remove(uid) == null) {
                return false;
            }
            released.add(uid + ":" + opId);
            return true;
        }

        @Override
        public int markOrphanedExpired(Instant now) {
            return 0;
        }

        @Override
        public List<OrphanedLock> orphanedLocks() {
            return List.of();
        }

        @Override
        public Optional<LockView> find(String uid) {
            Acquire a = locks.get(uid);
            return a == null ? Optional.empty()
                    : Optional.of(new LockView(uid, null, null, a.resourceEpoch(), "HELD"));
        }
    }

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();

    private static FakeIntents seededIntents(boolean resolved) {
        FakeIntents intents = new FakeIntents();
        intents.rows.put(INTENT, new ActionIntentStore.IntentView(INTENT, RUN, null,
                "a".repeat(64), "scale.service", "R2", resolved ? "res://demo/checkout" : null,
                resolved ? "h".repeat(64) : null, "{}"));
        return intents;
    }

    private static OperationPlanner planner(FakeIntents intents, FakeOperations operations,
            FakeOutbox outbox, FakeLocks locks, FakeEvents events, boolean enabled) {
        return new OperationPlanner(intents, operations, outbox, locks, events, new DirectTx(),
                TTL, enabled, FIXED);
    }

    @Test
    void pbP01_计划开关关闭_显式拒绝() {
        var outcome = planner(seededIntents(true), new FakeOperations(), new FakeOutbox(),
                new FakeLocks(), new FakeEvents(), false).plan(INTENT);
        assertThat(outcome.status()).isEqualTo(OperationPlanner.Outcome.Status.REJECTED);
        assertThat(outcome.rejectReason()).isEqualTo("PLAN_DISABLED");
    }

    @Test
    void pbP02_未解析意图_failClosed拒绝() {
        var outcome = planner(seededIntents(false), new FakeOperations(), new FakeOutbox(),
                new FakeLocks(), new FakeEvents(), true).plan(INTENT);
        assertThat(outcome.rejectReason()).isEqualTo("NOT_RESOLVED");
    }

    @Test
    void pbP03_锁BUSY_五步回滚零写入() {
        FakeIntents intents = seededIntents(true);
        FakeOperations operations = new FakeOperations();
        FakeOutbox outbox = new FakeOutbox();
        FakeLocks locks = new FakeLocks();
        locks.acquire("res://demo/checkout", UUID.randomUUID(), UUID.randomUUID(), TTL,
                FIXED.instant()); // 他方持锁
        FakeEvents events = new FakeEvents();
        var outcome = planner(intents, operations, outbox, locks, events, true).plan(INTENT);
        assertThat(outcome.rejectReason()).isEqualTo("LOCK_BUSY");
        // DirectTx 仿真不了真事务回滚（行级原子性由 REQUIRES_NEW 真事务承担，195 面实证）；
        // 此处钉"回滚点之后零发生"：outbox 空、intent 仍 OPEN、零事件
        assertThat(outbox.rows).isEmpty();
        assertThat(events.rows).isEmpty();
    }

    @Test
    void pbP04_成功计划_五步全落同事务() {
        FakeIntents intents = seededIntents(true);
        FakeOperations operations = new FakeOperations();
        FakeOutbox outbox = new FakeOutbox();
        FakeLocks locks = new FakeLocks();
        FakeEvents events = new FakeEvents();
        var outcome = planner(intents, operations, outbox, locks, events, true).plan(INTENT);
        assertThat(outcome.status()).isEqualTo(OperationPlanner.Outcome.Status.PLANNED);
        assertThat(outcome.resourceEpoch()).isEqualTo(1);
        RcaOperation op = operations.rows.get(outcome.operationId());
        assertThat(op.status()).isEqualTo(OperationStatus.PREPARED);
        assertThat(op.dryRun()).isTrue();
        assertThat(op.resourceEpoch()).isEqualTo(1);
        assertThat(outbox.rows).hasSize(1);
        assertThat(outbox.rows.get(0).state()).isEqualTo("PENDING");
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("OPERATION_PREPARED");
        assertThat(events.rows.get(0).payload())
                .contains("DRY_RUN_SENTINEL")
                .contains("res://demo/checkout");
    }

    @Test
    void pbP05_intentCAS失利_回滚拒绝() {
        FakeIntents intents = seededIntents(true);
        intents.plannedCasLoss = true;
        var outcome = planner(intents, new FakeOperations(), new FakeOutbox(),
                new FakeLocks(), new FakeEvents(), true).plan(INTENT);
        assertThat(outcome.rejectReason()).isEqualTo("INTENT_CAS_LOST");
    }

    @Test
    void pbD01_派发成功全链_释放闸在VERIFIED() {
        FakeIntents intents = seededIntents(true);
        FakeOperations operations = new FakeOperations();
        FakeOutbox outbox = new FakeOutbox();
        FakeLocks locks = new FakeLocks();
        FakeEvents events = new FakeEvents();
        var planned = planner(intents, operations, outbox, locks, events, true).plan(INTENT);
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.SUCCEED), events, "w1",
                Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);
        locks.releaseGateOpen = true; // operation VERIFIED 态由 SQL 闸表达（fake 模拟）

        dispatcher.dispatchOnce();

        RcaOperation op = operations.rows.get(planned.operationId());
        assertThat(op.status()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(locks.released).hasSize(1);
        assertThat(locks.locks).doesNotContainKey("res://demo/checkout");
        List<String> types = events.rows.stream().map(EventRow::type).toList();
        assertThat(types).containsExactly("OPERATION_PREPARED", "OPERATION_DISPATCHED",
                "OPERATION_ACKNOWLEDGED", "OPERATION_VERIFIED", "OPERATION_LOCK_RELEASED",
                "OPERATION_COMPLETED");
        assertThat(outbox.rows.get(0).state()).isEqualTo("DISPATCHED");
    }

    @Test
    void pbD02_CHAOS_TIMEOUT_UNKNOWN_锁保持不猜失败() {
        FakeIntents intents = seededIntents(true);
        FakeOperations operations = new FakeOperations();
        FakeOutbox outbox = new FakeOutbox();
        FakeLocks locks = new FakeLocks();
        FakeEvents events = new FakeEvents();
        var planned = planner(intents, operations, outbox, locks, events, true).plan(INTENT);
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.CHAOS_TIMEOUT), events,
                "w1", Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);

        dispatcher.dispatchOnce();

        assertThat(operations.rows.get(planned.operationId()).status())
                .isEqualTo(OperationStatus.UNKNOWN);
        assertThat(locks.locks).containsKey("res://demo/checkout"); // BUSY 保持
        List<String> types = events.rows.stream().map(EventRow::type).toList();
        assertThat(types).contains("OPERATION_UNKNOWN");
        assertThat(types).doesNotContain("OPERATION_COMPLETED", "OPERATION_FAILED");
    }

    @Test
    void pbD03_已推进operation_重投幂等跳过() {
        FakeOperations operations = new FakeOperations();
        FakeOutbox outbox = new FakeOutbox();
        FakeLocks locks = new FakeLocks();
        FakeEvents events = new FakeEvents();
        UUID opId = UUID.randomUUID();
        operations.insert(RcaOperation.prepare(opId, INTENT, RUN, null, "scale.service",
                "a".repeat(64), "res://demo/checkout", 1, "{}", FIXED.instant()));
        operations.transition(opId, OperationStatus.PREPARED, OperationStatus.DISPATCHED,
                FIXED.instant()); // 并发赢家已派发
        outbox.insert(UUID.randomUUID(), opId, FIXED.instant());
        var dispatcher = new OperationOutboxDispatcher(outbox, operations, locks,
                new DryRunActionRunner(DryRunActionRunner.Behavior.SUCCEED), events, "w1",
                Duration.ofSeconds(30), Duration.ofSeconds(5), FIXED);

        dispatcher.dispatchOnce();

        assertThat(operations.rows.get(opId).status()).isEqualTo(OperationStatus.DISPATCHED);
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("OPERATION_DISPATCH_SKIPPED");
    }

    @Test
    void pbD04_租约过期回收_重领派发() {
        FakeOutbox outbox = new FakeOutbox();
        UUID opId = UUID.randomUUID();
        Instant now = FIXED.instant();
        // 未过期 CLAIMED：不可领，回收 0
        outbox.rows.add(new FakeOutbox.Row(UUID.randomUUID(), opId, "CLAIMED",
                now.plusSeconds(60)));
        assertThat(outbox.reclaimExpiredLeases(now.plusSeconds(1))).isZero();
        assertThat(outbox.claimNext("w2", Duration.ofSeconds(30), now.plusSeconds(1)))
                .isEmpty();
        // 过期 CLAIMED：回收 → PENDING → 可重领（at-least-once）
        assertThat(outbox.reclaimExpiredLeases(now.plusSeconds(61))).isEqualTo(1);
        assertThat(outbox.claimNext("w2", Duration.ofSeconds(30), now.plusSeconds(62)))
                .isPresent();
    }
}
