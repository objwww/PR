package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate;
import com.objwww.pr.control.alert.application.approval.ApprovalStore;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionCallback;

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
 * 消费模板真锚单测（PC-C2，§2.8 步骤 1/2 落地）：无 grant=NO_GRANT 零写入；
 * 有活 grant=配额 CAS+single-use 授权随 PREPARED 同事务签发并消费（grant 锚入
 * 事件、哨兵字样消失）；ONCE grant 二次计划=NO_GRANT（EXHAUSTED）。
 */
class PlannerApprovalGateTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-16T02:00:00Z"),
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

    static final class DirectTx implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    static final class FakeIntents implements com.objwww.pr.control.alert.application.mutation.ActionIntentStore {
        final Map<UUID, com.objwww.pr.control.alert.application.mutation.ActionIntentStore.IntentView> rows =
                new HashMap<>();
        final java.util.Set<UUID> planned = new java.util.HashSet<>();

        @Override
        public Optional<com.objwww.pr.control.alert.application.mutation.ActionIntentStore.IntentView> findById(
                UUID intentId) {
            return Optional.ofNullable(rows.get(intentId));
        }

        @Override
        public boolean markResolved(UUID intentId, String uid, String json, String hash,
                Instant at) {
            return false;
        }

        @Override
        public boolean markPlanned(UUID intentId, UUID operationId, Instant at) {
            return planned.add(intentId); // 单向 CAS 仿真：恰一赢家
        }
    }

    static final class FakeGate implements ApprovalPlannerGate {
        ApprovalStore.GrantView grant;
        final Map<UUID, String> authzs = new HashMap<>();

        @Override
        public Optional<ApprovalStore.GrantView> findActiveGrant(UUID runId,
                String actionDigest, String snapshotHash, String policyVersion) {
            if (grant == null || !"ACTIVE".equals(grant.state())) {
                return Optional.empty();
            }
            return Optional.of(grant);
        }

        @Override
        public boolean tryReserveGrantQuota(UUID grantId, Instant at) {
            if (grant == null || grant.issuedOperations() >= grant.maxOperations()) {
                return false;
            }
            grant = new ApprovalStore.GrantView(grant.grantId(), grant.requestId(),
                    grant.runId(), grant.actionId(), grant.actionDigest(),
                    grant.scopeSnapshotHash(), grant.policyVersion(), grant.scopeKind(),
                    grant.maxOperations(), grant.issuedOperations() + 1, "EXHAUSTED",
                    grant.expiresAt());
            return true;
        }

        @Override
        public UUID insertAuthorization(UUID grantId, Instant at) {
            UUID id = UUID.randomUUID();
            authzs.put(id, "ISSUED");
            return id;
        }

        @Override
        public boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at) {
            return authzs.replace(authzId, "ISSUED", "CONSUMED");
        }
    }

    private static final String DIGEST = "a".repeat(64);

    private static FakeIntents intents(UUID intentId, UUID runId) {
        FakeIntents intents = new FakeIntents();
        intents.rows.put(intentId, new com.objwww.pr.control.alert.application.mutation.ActionIntentStore.IntentView(
                intentId, runId, null, DIGEST, "scale.service", "R2",
                "res://demo/checkout", "h".repeat(64), "{}"));
        return intents;
    }

    @Test
    void pcP01_无grant_NO_GRANT() {
        UUID intentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var operations = new InMemOperations();
        var outcome = new OperationPlanner(intents(intentId, runId),
                operations, new InMemOutbox(), new InMemLocks(), new FakeGate(),
                new FakeEvents(), new DirectTx(), TTL, true, true, "pb-prod-v1", FIXED)
                .plan(intentId);
        assertThat(outcome.rejectReason()).isEqualTo("NO_GRANT");
        assertThat(operations.rows).isEmpty(); // 回滚点之前零写入
    }

    @Test
    void pcP02_真锚全链_授权同事务消费_ONCE二次NO_GRANT() {
        UUID runId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        FakeIntents intents = intents(intentId, runId);
        InMemOperations operations = new InMemOperations();
        InMemLocks locks = new InMemLocks();
        FakeEvents events = new FakeEvents();
        FakeGate gate = new FakeGate();
        gate.grant = new ApprovalStore.GrantView(UUID.randomUUID(), UUID.randomUUID(),
                runId, "scale.service", DIGEST, "h".repeat(64), "pb-prod-v1",
                "ONCE", 1, 0, "ACTIVE", FIXED.instant().plus(TTL));
        OperationPlanner planner = new OperationPlanner(intents, operations,
                new InMemOutbox(), locks, gate, events, new DirectTx(), TTL, true, true,
                "pb-prod-v1", FIXED);

        var outcome = planner.plan(intentId);

        assertThat(outcome.status()).isEqualTo(OperationPlanner.Outcome.Status.PLANNED);
        assertThat(gate.authzs.values()).containsExactly("CONSUMED"); // single-use 恰一次
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("OPERATION_PREPARED");
        assertThat(events.rows.get(0).payload()).contains("grant_id");
        assertThat(events.rows.get(0).payload()).doesNotContain("DRY_RUN_SENTINEL");
        // ONCE grant 已 EXHAUSTED → findActiveGrant 空 → NO_GRANT
        var second = planner.plan(intentId);
        assertThat(second.rejectReason()).isEqualTo("NO_GRANT");
    }

    // ---- 最小内存实现（B 波既有 fake 的同型精简版） ----

    static final class InMemOperations implements OperationLedgerStore {
        final Map<UUID, com.objwww.pr.control.alert.domain.mutation.RcaOperation> rows =
                new HashMap<>();

        @Override
        public void insert(com.objwww.pr.control.alert.domain.mutation.RcaOperation operation) {
            rows.put(operation.operationId(), operation);
        }

        @Override
        public void updateResourceEpoch(UUID operationId, long resourceEpoch) {
        }

        @Override
        public Optional<com.objwww.pr.control.alert.domain.mutation.RcaOperation> findById(
                UUID operationId) {
            return Optional.ofNullable(rows.get(operationId));
        }

        @Override
        public boolean transition(UUID operationId,
                com.objwww.pr.control.alert.domain.mutation.OperationStatus expected,
                com.objwww.pr.control.alert.domain.mutation.OperationStatus next, Instant at) {
            return false;
        }

        @Override
        public List<UUID> idsInStatus(
                com.objwww.pr.control.alert.domain.mutation.OperationStatus status) {
            return List.of();
        }

        @Override
        public boolean hasActiveForRun(UUID runId) {
            return false;
        }
    }

    static final class InMemOutbox implements OperationOutboxStore {
        final List<UUID> inserted = new ArrayList<>();

        @Override
        public void insert(UUID outboxId, UUID operationId, Instant now) {
            inserted.add(operationId);
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
            return false;
        }

        @Override
        public Optional<OutboxView> findByOperation(UUID operationId) {
            return Optional.empty();
        }
    }

    static final class InMemLocks implements ResourceLockStore {
        final Map<String, Acquire> locks = new HashMap<>();

        @Override
        public Acquire acquire(String uid, UUID opId, UUID runId, Duration ttl, Instant now) {
            if (locks.containsKey(uid)) {
                return Acquire.busy(uid);
            }
            Acquire acquire = new Acquire(uid, true, 1);
            locks.put(uid, acquire);
            return acquire;
        }

        @Override
        public boolean releaseOnTerminalState(String uid, UUID opId) {
            return locks.remove(uid) != null;
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
}
