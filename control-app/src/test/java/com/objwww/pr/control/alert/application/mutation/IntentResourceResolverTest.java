package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ResolvedResource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
 * 意图解析推进 + 授权扩张提案单测（PB-B2，fake 端口）：fail-closed（Resolver miss
 * 不产出任何身份/不落快照）、一次性解析（重复/CAS 失利全拒）、扩张必须解析成功
 * 才有提案、PENDING_APPROVAL 无放行权。
 */
class IntentResourceResolverTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-15T16:00:00Z"),
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

    /** 直通事务（单测无 Spring 事务面） */
    static final class DirectTx implements org.springframework.transaction.support.TransactionOperations {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    static final class FakeStore implements ActionIntentStore {
        final Map<UUID, IntentView> rows = new HashMap<>();
        boolean casSabotage;

        void seed(UUID intentId, UUID runId) {
            rows.put(intentId, new IntentView(intentId, runId, null, "a".repeat(64),
                    "scale.service", "R2", null, null, "{}"));
        }

        @Override
        public Optional<IntentView> findById(UUID intentId) {
            return Optional.ofNullable(rows.get(intentId));
        }

        @Override
        public boolean markResolved(UUID intentId, String uid, String json, String hash,
                Instant at) {
            IntentView view = rows.get(intentId);
            if (view == null || view.resolvedResourceUid() != null || casSabotage) {
                return false;
            }
            rows.put(intentId, new IntentView(intentId, view.runId(), view.taskId(),
                    view.actionDigest(), view.toolName(), view.risk(), uid, hash, view.argsJson()));
            return true;
        }

        @Override
        public boolean markPlanned(UUID intentId, UUID operationId, Instant at) {
            return false;
        }
    }

    static final class FakeResolver implements ResourceResolver {
        final Map<String, ResolvedResource> table = new HashMap<>();

        @Override
        public Optional<ResolvedResource> resolve(String requestedKey) {
            return Optional.ofNullable(table.get(requestedKey));
        }
    }

    static final class FakeExpansions implements ScopeExpansionLedger {
        record Row(UUID id, UUID runId, String uid, String status, String approvalId) {
        }
        final List<Row> rows = new ArrayList<>();

        @Override
        public UUID recordPending(UUID runId, String requestedKey, String uid, String reason,
                String snapshotJson, String snapshotHash, Instant at) {
            UUID id = UUID.randomUUID();
            rows.add(new Row(id, runId, uid, "PENDING_APPROVAL", null));
            return id;
        }

        @Override
        public boolean hasApprovedExpansion(UUID runId, String resourceUid) {
            return rows.stream().anyMatch(r -> r.runId().equals(runId)
                    && r.uid().equals(resourceUid) && "APPROVED".equals(r.status()));
        }
    }

    private static final ResolvedResource CHECKOUT = new ResolvedResource(
            "res://demo/checkout", "checkout", "demo", "payments", "service", 3, Map.of());

    @Test
    void pbR01_解析成功_快照锚写入_事件留痕() {
        UUID intentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FakeResolver resolver = new FakeResolver();
        resolver.table.put("checkout", CHECKOUT);
        FakeStore store = new FakeStore();
        store.seed(intentId, runId);
        FakeEvents events = new FakeEvents();
        var service = new IntentResourceResolver(resolver, store, events, new DirectTx(),
                "pb-prod-v1", FIXED);

        var outcome = service.resolveAndRecord(intentId, "checkout");

        assertThat(outcome.resolved()).isTrue();
        assertThat(outcome.resourceUid()).isEqualTo("res://demo/checkout");
        assertThat(outcome.snapshotHash()).hasSize(64);
        assertThat(store.rows.get(intentId).resolvedResourceUid())
                .isEqualTo("res://demo/checkout");
        assertThat(store.rows.get(intentId).scopeSnapshotHash())
                .isEqualTo(outcome.snapshotHash());
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("INTENT_RESOURCE_RESOLVED");
        assertThat(events.rows.get(0).payload()).contains(outcome.snapshotHash());
    }

    @Test
    void pbR02_ResolverMiss_failClosed_零身份产出() {
        UUID intentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FakeStore store = new FakeStore();
        store.seed(intentId, runId);
        FakeEvents events = new FakeEvents();
        var service = new IntentResourceResolver(new FakeResolver(), store, events,
                new DirectTx(), "pb-prod-v1", FIXED);

        var outcome = service.resolveAndRecord(intentId, "ghost-key");

        assertThat(outcome.resolved()).isFalse();
        assertThat(store.rows.get(intentId).resolvedResourceUid()).isNull(); // 快照不落
        assertThat(store.rows.get(intentId).scopeSnapshotHash()).isNull();
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("INTENT_RESOLVE_FAILED");
        assertThat(events.rows.get(0).payload()).contains("RESOLVER_MISS");
    }

    @Test
    void pbR03_重复解析与CAS失利_全拒() {
        UUID intentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FakeResolver resolver = new FakeResolver();
        resolver.table.put("checkout", CHECKOUT);
        FakeStore store = new FakeStore();
        store.seed(intentId, runId);
        var service = new IntentResourceResolver(resolver, store, new FakeEvents(),
                new DirectTx(), "pb-prod-v1", FIXED);
        service.resolveAndRecord(intentId, "checkout");
        assertThatThrownBy(() -> service.resolveAndRecord(intentId, "checkout"))
                .isInstanceOf(IllegalStateException.class);

        // CAS 失利（并发双写另一路先赢）= 显式异常，不静默
        UUID other = UUID.randomUUID();
        store.seed(other, runId);
        store.casSabotage = true;
        assertThatThrownBy(() -> service.resolveAndRecord(other, "checkout"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pbE01_扩张提案_PENDING无锚_不具放行权() {
        UUID runId = UUID.randomUUID();
        FakeResolver resolver = new FakeResolver();
        resolver.table.put("gateway", new ResolvedResource("res://demo/gateway", "gateway",
                "demo", "web", "service", 1, Map.of()));
        FakeExpansions ledger = new FakeExpansions();
        FakeEvents events = new FakeEvents();
        var service = new ScopeExpansionService(resolver, ledger, events, new DirectTx(),
                "pb-prod-v1", FIXED);

        UUID id = service.propose(runId, "gateway", "根因在 upstream gateway（域外）");

        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(ledger.rows.get(0).approvalId()).isNull(); // 锚位 Phase C 回填
        assertThat(ledger.hasApprovedExpansion(runId, "res://demo/gateway")).isFalse();
        assertThat(events.rows).hasSize(1);
        assertThat(events.rows.get(0).type()).isEqualTo("SCOPE_EXPANSION_PROPOSED");
        assertThat(id).isEqualTo(ledger.rows.get(0).id());
    }

    @Test
    void pbE02_扩张ResolverMiss_提案不存在() {
        FakeExpansions ledger = new FakeExpansions();
        var service = new ScopeExpansionService(new FakeResolver(), ledger, new FakeEvents(),
                new DirectTx(), "pb-prod-v1", FIXED);
        assertThatThrownBy(() -> service.propose(UUID.randomUUID(), "ghost", "理由"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESOLVER_MISS");
        assertThat(ledger.rows).isEmpty();
        // 理由为审计锚，空理由拒绝
        assertThatThrownBy(() -> service.propose(UUID.randomUUID(), "checkout", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
