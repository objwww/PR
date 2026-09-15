package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.OperationStateMachine;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import com.objwww.pr.shared.IllegalTransitionException;
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
 * PB-B5 单测：mutation 对账（UNKNOWN→RECONCILING→裁决 / PREPARED 悬挂 ESCALATE）+
 * 「reconcile 先于 reschedule」闸口 + 事件时间线重建（Phase B 退出准则断言面）。
 */
class Pb5ReconcileRebuildTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-15T19:00:00Z"),
            ZoneOffset.UTC);

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

    static final class FakeOperations implements OperationLedgerStore {
        final Map<UUID, RcaOperation> rows = new HashMap<>();
        final List<UUID> insertions = new ArrayList<>();

        @Override
        public void insert(RcaOperation operation) {
            rows.put(operation.operationId(), operation);
            insertions.add(operation.operationId());
        }

        @Override
        public void updateResourceEpoch(UUID operationId, long resourceEpoch) {
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
                    && op.status().holdsResourceLock()
                    && op.status() != OperationStatus.ESCALATED);
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
            return Optional.empty();
        }

        @Override
        public int reclaimExpiredLeases(Instant now) {
            return 0;
        }

        @Override
        public boolean markDispatched(UUID outboxId, Instant now) {
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
        final Map<String, Boolean> locks = new HashMap<>();
        final List<String> released = new ArrayList<>();
        boolean gateOpen = true;

        @Override
        public Acquire acquire(String uid, UUID opId, UUID runId, Duration ttl, Instant now) {
            if (Boolean.TRUE.equals(locks.get(uid))) {
                return Acquire.busy(uid);
            }
            locks.put(uid, true);
            return new Acquire(uid, true, 1);
        }

        @Override
        public boolean releaseOnTerminalState(String uid, UUID opId) {
            if (!gateOpen || !Boolean.TRUE.equals(locks.remove(uid))) {
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
            return Optional.empty();
        }
    }

    private static RcaOperation opIn(OperationStatus status, UUID runId) {
        UUID id = UUID.randomUUID();
        RcaOperation op = RcaOperation.prepare(id, UUID.randomUUID(), runId, null,
                "scale.service", "a".repeat(64), "res://demo/checkout", 1, "{}", FIXED.instant());
        return walkTo(op, status);
    }

    private static RcaOperation walkTo(RcaOperation op, OperationStatus target) {
        if (op.status() == target) {
            return op;
        }
        // 状态机边上的 BFS 最短路（禁越边，路径必然合法）
        Map<OperationStatus, OperationStatus> parent = new HashMap<>();
        List<OperationStatus> queue = new ArrayList<>(List.of(op.status()));
        parent.put(op.status(), op.status());
        for (int i = 0; i < queue.size() && !parent.containsKey(target); i++) {
            OperationStatus cur = queue.get(i);
            for (OperationStatus candidate : OperationStatus.values()) {
                if (!parent.containsKey(candidate)
                        && OperationStateMachine.canTransition(cur, candidate)) {
                    parent.put(candidate, cur);
                    queue.add(candidate);
                }
            }
        }
        if (!parent.containsKey(target)) {
            throw new IllegalStateException("不可达: " + target);
        }
        List<OperationStatus> path = new ArrayList<>();
        for (OperationStatus cur = target; cur != op.status(); cur = parent.get(cur)) {
            path.add(cur);
        }
        java.util.Collections.reverse(path);
        Instant t = FIXED.instant();
        for (OperationStatus step : path) {
            op = op.withStatus(step, t.plusSeconds(path.indexOf(step) + 1L));
        }
        return op;
    }

    @Test
    void pbR01_UNKNOWN走链_VERIFIED裁决_放锁至COMPLETED() {
        UUID runId = UUID.randomUUID();
        FakeOperations operations = new FakeOperations();
        RcaOperation seeded = opIn(OperationStatus.UNKNOWN, runId);
        operations.rows.put(seeded.operationId(), seeded);
        UUID opId = seeded.operationId();
        FakeLocks locks = new FakeLocks();
        locks.locks.put("res://demo/checkout", true);
        FakeEvents events = new FakeEvents();
        var reconciler = new OperationReconciler(operations, new FakeOutbox(), locks, events,
                OperationReconciler.Verdict.VERIFIED, Duration.ofMinutes(10), Duration.ofMinutes(1),
                FIXED);

        int handled = reconciler.reconcileOnce();

        assertThat(handled).isEqualTo(1);
        assertThat(operations.rows.get(opId).status()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(locks.released).hasSize(1);
        List<String> types = events.rows.stream().map(EventRow::type).toList();
        assertThat(types).containsExactly("OPERATION_RECONCILING", "OPERATION_RECONCILED_VERIFIED",
                "OPERATION_LOCK_RELEASED", "OPERATION_COMPLETED");
    }

    @Test
    void pbR02_RETRYABLE裁决_outbox回PENDING_锁保持重派() {
        UUID runId = UUID.randomUUID();
        FakeOperations operations = new FakeOperations();
        RcaOperation seeded = opIn(OperationStatus.UNKNOWN, runId);
        operations.rows.put(seeded.operationId(), seeded);
        UUID opId = seeded.operationId();
        FakeOutbox outbox = new FakeOutbox();
        outbox.rows.add(new FakeOutbox.Row(UUID.randomUUID(), opId, "DISPATCHED", null));
        FakeLocks locks = new FakeLocks();
        locks.locks.put("res://demo/checkout", true);
        FakeEvents events = new FakeEvents();
        var reconciler = new OperationReconciler(operations, outbox, locks, events,
                OperationReconciler.Verdict.RETRYABLE, Duration.ofMinutes(10),
                Duration.ofMinutes(1), FIXED);

        reconciler.reconcileOnce();

        assertThat(operations.rows.get(opId).status()).isEqualTo(OperationStatus.RETRYABLE);
        assertThat(outbox.rows.get(0).state()).isEqualTo("PENDING"); // 同行重派
        assertThat(locks.locks).containsKey("res://demo/checkout"); // 锁保持
        assertThat(events.rows.stream().map(EventRow::type))
                .contains("OPERATION_RETRYABLE");
    }

    @Test
    void pbR03_PREPARED悬挂_ESCALATED_锁保持至人工() {
        UUID runId = UUID.randomUUID();
        FakeOperations operations = new FakeOperations();
        RcaOperation seeded = opIn(OperationStatus.PREPARED, runId);
        operations.rows.put(seeded.operationId(), seeded);
        UUID opId = seeded.operationId();
        FakeOutbox outbox = new FakeOutbox(); // 无 outbox 行 = 派发指令丢失
        FakeLocks locks = new FakeLocks();
        locks.locks.put("res://demo/checkout", true);
        FakeEvents events = new FakeEvents();
        var reconciler = new OperationReconciler(operations, outbox, locks, events,
                OperationReconciler.Verdict.VERIFIED, Duration.ofMinutes(10),
                Duration.ofMinutes(1), FIXED);

        reconciler.reconcileOnce();

        assertThat(operations.rows.get(opId).status()).isEqualTo(OperationStatus.ESCALATED);
        assertThat(locks.locks).containsKey("res://demo/checkout"); // ESCALATED 不释放
        assertThat(events.rows.stream().map(EventRow::type)).contains("OPERATION_ESCALATED");
    }

    @Test
    void pbG01_reschedule闸_活跃mutation拒_无mutation放() {
        UUID runId = UUID.randomUUID();
        FakeOperations operations = new FakeOperations();
        RcaOperation seeded = opIn(OperationStatus.UNKNOWN, runId);
        operations.rows.put(seeded.operationId(), seeded);
        var gate = new MutationActiveGate(operations);
        assertThat(gate.hasActiveMutation(runId)).isTrue(); // UNKNOWN 未穿越中间态
        // ESCALATED 终态：已到人工裁决出口，闸放行（reschedule 不再被 mutation 阻塞
        // ——锁保持是资源面语义，不是 run 面语义）
        UUID opId = seeded.operationId();
        operations.transition(opId, OperationStatus.UNKNOWN, OperationStatus.RECONCILING,
                FIXED.instant());
        operations.transition(opId, OperationStatus.RECONCILING, OperationStatus.ESCALATED,
                FIXED.instant());
        assertThat(gate.hasActiveMutation(runId)).isFalse();
    }

    @Test
    void pbT01_事件重建_成功全链与UNKNOWN走链_皆与状态机自洽() {
        UUID opA = UUID.randomUUID();
        UUID opB = UUID.randomUUID();
        List<OperationTimelineRebuilder.Row> rows = List.of(
                new OperationTimelineRebuilder.Row(opA, "OPERATION_PREPARED"),
                new OperationTimelineRebuilder.Row(opA, "OPERATION_DISPATCHED"),
                new OperationTimelineRebuilder.Row(opA, "OPERATION_ACKNOWLEDGED"),
                new OperationTimelineRebuilder.Row(opA, "OPERATION_VERIFIED"),
                new OperationTimelineRebuilder.Row(opA, "OPERATION_COMPLETED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_PREPARED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_DISPATCHED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_UNKNOWN"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_RECONCILING"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_RETRYABLE"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_DISPATCHED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_ACKNOWLEDGED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_VERIFIED"),
                new OperationTimelineRebuilder.Row(opB, "OPERATION_COMPLETED"));
        var rebuild = OperationTimelineRebuilder.rebuild(rows);
        assertThat(rebuild.finalStatuses().get(opA)).isEqualTo(OperationStatus.COMPLETED);
        assertThat(rebuild.finalStatuses().get(opB)).isEqualTo(OperationStatus.COMPLETED);
    }

    @Test
    void pbT02_事件缺环_重建必炸_审计不完整可检测() {
        UUID op = UUID.randomUUID();
        // UNKNOWN 后直接 RETRYABLE（缺 RECONCILING 中间态事件）→ 跳越即 IllegalTransition
        List<OperationTimelineRebuilder.Row> broken = List.of(
                new OperationTimelineRebuilder.Row(op, "OPERATION_PREPARED"),
                new OperationTimelineRebuilder.Row(op, "OPERATION_DISPATCHED"),
                new OperationTimelineRebuilder.Row(op, "OPERATION_UNKNOWN"),
                new OperationTimelineRebuilder.Row(op, "OPERATION_RETRYABLE"),
                new OperationTimelineRebuilder.Row(op, "OPERATION_DISPATCHED"));
        assertThatThrownBy(() -> OperationTimelineRebuilder.rebuild(broken))
                .isInstanceOf(IllegalTransitionException.class);
        // 起点'))->非 PREPARED 事件同样拒绝
        List<OperationTimelineRebuilder.Row> headless = List.of(
                new OperationTimelineRebuilder.Row(op, "OPERATION_DISPATCHED"));
        assertThatThrownBy(() -> OperationTimelineRebuilder.rebuild(headless))
                .isInstanceOf(IllegalTransitionException.class);
    }
}
