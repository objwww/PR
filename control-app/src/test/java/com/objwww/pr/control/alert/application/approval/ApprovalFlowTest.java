package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.application.mutation.ActionIntentStore;
import com.objwww.pr.control.alert.domain.approval.DecisionQuorum;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批流服务单测（PC-C1，fake 端口）：请求铸造（稳定事实锚/fail-closed）、决策流
 * （同人重复拒/双人批准→Grant 签发/单批 R2/终态拒新决策）、过期清扫事件。
 */
class ApprovalFlowTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"),
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
            return false;
        }
    }

    static final class FakeApprovalStore implements ApprovalStore {
        final Map<UUID, RequestView> requests = new HashMap<>();
        final Map<UUID, List<DecisionQuorum.Decision>> decisions = new HashMap<>();
        final Set<UUID> duplicateDecisions = java.util.Collections.newSetFromMap(new HashMap<>());
        final Map<UUID, GrantView> grants = new HashMap<>();
        final Map<UUID, AuthzView> authzs = new HashMap<>();
        int generation = 7;

        @Override
        public int generationOfRun(UUID runId) {
            return generation;
        }

        @Override
        public void insertRequest(RequestView r) {
            requests.put(r.requestId(), r);
        }

        @Override
        public Optional<RequestView> findRequest(UUID requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public boolean insertDecision(UUID requestId, String approverId, String approverRole,
                boolean approved, Instant at) {
            if (!duplicateDecisions.add(UUID.nameUUIDFromBytes(
                    (requestId + approverId).getBytes()))) {
                return false; // 同人重复（UNIQUE 模拟）
            }
            decisions.computeIfAbsent(requestId, k -> new ArrayList<>())
                    .add(new DecisionQuorum.Decision(approverId, approverRole, approved));
            return true;
        }

        @Override
        public List<DecisionQuorum.Decision> listDecisions(UUID requestId) {
            return List.copyOf(decisions.getOrDefault(requestId, List.of()));
        }

        @Override
        public boolean markRequestState(UUID requestId, String expectedState,
                String nextState, Instant decidedAt, String voidReason) {
            RequestView view = requests.get(requestId);
            if (view == null || !expectedState.equals(view.state())) {
                return false;
            }
            requests.put(requestId, new RequestView(view.requestId(), view.intentId(),
                    view.runId(), view.actionId(), view.actionDigest(),
                    view.observedGeneration(), view.requiredApprovers(),
                    view.scopeSnapshotHash(), view.policyVersion(), nextState,
                    view.expiresAt()));
            return true;
        }

        @Override
        public List<ExpiredRequest> expirePendingRequests(Instant now) {
            List<ExpiredRequest> expired = new ArrayList<>();
            for (RequestView view : List.copyOf(requests.values())) {
                if ("PENDING".equals(view.state()) && view.expiresAt().isBefore(now)) {
                    markRequestState(view.requestId(), "PENDING", "EXPIRED", null, null);
                    expired.add(new ExpiredRequest(view.requestId(), view.runId()));
                }
            }
            return expired;
        }

        @Override
        public void insertGrant(GrantView g) {
            grants.put(g.grantId(), g);
        }

        @Override
        public Optional<GrantView> findActiveGrant(UUID runId, String actionDigest,
                String snapshotHash, String policyVersion) {
            return grants.values().stream()
                    .filter(g -> "ACTIVE".equals(g.state()) && g.expiresAt().isAfter(FIXED.instant())
                            && g.runId().equals(runId) && g.actionDigest().equals(actionDigest)
                            && g.scopeSnapshotHash().equals(snapshotHash)
                            && g.policyVersion().equals(policyVersion))
                    .findFirst();
        }

        @Override
        public boolean tryReserveGrantQuota(UUID grantId, Instant at) {
            GrantView g = grants.get(grantId);
            if (g == null || !"ACTIVE".equals(g.state()) || g.expiresAt().isBefore(at)
                    || g.issuedOperations() >= g.maxOperations()) {
                return false;
            }
            boolean exhausted = g.issuedOperations() + 1 >= g.maxOperations();
            grants.put(grantId, new GrantView(g.grantId(), g.requestId(), g.runId(),
                    g.actionId(), g.actionDigest(), g.scopeSnapshotHash(),
                    g.policyVersion(), g.scopeKind(), g.maxOperations(),
                    g.issuedOperations() + 1, exhausted ? "EXHAUSTED" : "ACTIVE",
                    g.expiresAt()));
            return true;
        }

        @Override
        public Optional<GrantView> findGrant(UUID grantId) {
            return Optional.ofNullable(grants.get(grantId));
        }

        @Override
        public boolean markGrantState(UUID grantId, String expectedState, String nextState,
                Instant at) {
            return false;
        }

        @Override
        public List<ExpiredRequest> expireActiveGrants(Instant now) {
            List<ExpiredRequest> expired = new ArrayList<>();
            for (GrantView g : List.copyOf(grants.values())) {
                if ("ACTIVE".equals(g.state()) && g.expiresAt().isBefore(now)) {
                    grants.put(g.grantId(), new GrantView(g.grantId(), g.requestId(),
                            g.runId(), g.actionId(), g.actionDigest(),
                            g.scopeSnapshotHash(), g.policyVersion(), g.scopeKind(),
                            g.maxOperations(), g.issuedOperations(), "EXPIRED",
                            g.expiresAt()));
                    expired.add(new ExpiredRequest(g.grantId(), g.runId()));
                }
            }
            return expired;
        }

        @Override
        public UUID insertAuthorization(UUID grantId, Instant at) {
            UUID id = UUID.randomUUID();
            authzs.put(id, new AuthzView(id, grantId, null, "ISSUED"));
            return id;
        }

        @Override
        public boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at) {
            AuthzView a = authzs.get(authzId);
            if (a == null || !"ISSUED".equals(a.state())) {
                return false;
            }
            authzs.put(authzId, new AuthzView(authzId, a.grantId(), operationId, "CONSUMED"));
            return true;
        }

        @Override
        public Optional<AuthzView> findAuthorization(UUID authzId) {
            return Optional.ofNullable(authzs.get(authzId));
        }
    }

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();

    private static FakeIntents resolvedIntents(String risk) {
        FakeIntents intents = new FakeIntents();
        intents.rows.put(INTENT, new ActionIntentStore.IntentView(INTENT, RUN, null,
                "a".repeat(64), "scale.service", risk, "res://demo/checkout", "h".repeat(64),
                "{}"));
        return intents;
    }

    private static ApprovalRequestService requestService(FakeIntents intents,
            FakeApprovalStore store, FakeEvents events) {
        return new ApprovalRequestService(intents, store, events, new DirectTx(), "pb-prod-v1",
                FIXED);
    }

    private static ApprovalDecisionService decisionService(FakeApprovalStore store,
            FakeEvents events) {
        return new ApprovalDecisionService(store, events, new DirectTx(),
                Duration.ofMinutes(10), FIXED);
    }

    @Test
    void pcF01_请求铸造_稳定事实锚_双人策略() {
        FakeApprovalStore store = new FakeApprovalStore();
        FakeEvents events = new FakeEvents();
        UUID requestId = requestService(resolvedIntents("R3"), store, events).request(INTENT);
        var view = store.findRequest(requestId).orElseThrow();
        assertThat(view.state()).isEqualTo("PENDING");
        assertThat(view.observedGeneration()).isEqualTo(7); // run 代数快照
        assertThat(view.requiredApprovers()).isEqualTo(2); // R3 双人
        assertThat(view.scopeSnapshotHash()).hasSize(64);
        assertThat(events.rows.get(0).type()).isEqualTo("APPROVAL_REQUESTED");
        // R2 单批
        UUID r2Intent = UUID.randomUUID();
        FakeIntents intents = resolvedIntents("R2");
        intents.rows.put(r2Intent, intents.rows.get(INTENT));
        var r2 = requestService(intents, store, events)
                .request(r2Intent);
        assertThat(store.findRequest(r2).orElseThrow().requiredApprovers()).isEqualTo(1);
    }

    @Test
    void pcF02_未解析意图_failClosed拒审() {
        FakeIntents intents = new FakeIntents();
        intents.rows.put(INTENT, new ActionIntentStore.IntentView(INTENT, RUN, null,
                "a".repeat(64), "scale.service", "R2", null, null, "{}"));
        assertThatThrownBy(() -> requestService(intents, new FakeApprovalStore(),
                new FakeEvents()).request(INTENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pcF03_双人批准流_签发Grant() {
        FakeApprovalStore store = new FakeApprovalStore();
        FakeEvents events = new FakeEvents();
        UUID requestId = requestService(resolvedIntents("R3"), store, events).request(INTENT);
        var decisions = decisionService(store, events);
        decisions.decide(requestId, "oncall-a", "ONCALL", true);
        assertThat(store.findRequest(requestId).orElseThrow().state()).isEqualTo("PENDING");
        // 同人换角色仍被 UNIQUE(request_id, approver_id) 结构拒绝——一人一票
        assertThatThrownBy(() -> decisions.decide(requestId, "oncall-a", "SECURITY", true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.findRequest(requestId).orElseThrow().state()).isEqualTo("PENDING");
        decisions.decide(requestId, "sec-b", "SECURITY", true); // distinct principal+role
        assertThat(store.findRequest(requestId).orElseThrow().state()).isEqualTo("APPROVED");
        assertThat(store.grants).hasSize(1);
        var grant = store.grants.values().iterator().next();
        assertThat(grant.scopeKind()).isEqualTo("ONCE");
        assertThat(grant.maxOperations()).isEqualTo(1);
        assertThat(events.rows.stream().map(EventRow::type))
                .contains("APPROVAL_APPROVED", "GRANT_ISSUED");
    }

    @Test
    void pcF04_同人重复决策_结构拒绝() {
        FakeApprovalStore store = new FakeApprovalStore();
        FakeEvents events = new FakeEvents();
        UUID requestId = requestService(resolvedIntents("R2"), store, events).request(INTENT);
        var decisions = decisionService(store, events);
        decisions.decide(requestId, "oncall-a", "ONCALL", true);
        assertThatThrownBy(() -> decisions.decide(requestId, "oncall-a", "ONCALL", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decisions.decide(requestId, "oncall-a", "SECURITY", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pcF05_终态拒新决策_过期清扫落事件() {
        FakeApprovalStore store = new FakeApprovalStore();
        FakeEvents events = new FakeEvents();
        UUID requestId = requestService(resolvedIntents("R2"), store, events).request(INTENT);
        decisionService(store, events).decide(requestId, "oncall-a", "ONCALL", false);
        assertThat(store.findRequest(requestId).orElseThrow().state()).isEqualTo("DENIED");
        assertThatThrownBy(() -> decisionService(store, events)
                .decide(requestId, "sec-b", "SECURITY", true))
                .isInstanceOf(IllegalStateException.class);
        // 过期清扫：PENDING 300s 后 EXPIRED + 事件
        UUID pendingId = requestService(resolvedIntents("R2"), store, events).request(INTENT);
        var sweep = new ApprovalSweepLoop(store, events, Duration.ofSeconds(30), FIXED);
        assertThat(sweep.sweepOnce()).isZero(); // 未到期
        FakeApprovalStore expiredStore = new FakeApprovalStore();
        FakeEvents expiredEvents = new FakeEvents();
        UUID pending2 = requestService(resolvedIntents("R2"), expiredStore, expiredEvents)
                .request(INTENT);
        // 时间推进到 TTL 之后
        Clock later = Clock.fixed(FIXED.instant().plus(Duration.ofSeconds(301)),
                ZoneOffset.UTC);
        var laterSweep = new ApprovalSweepLoop(expiredStore, expiredEvents,
                Duration.ofSeconds(30), later);
        assertThat(laterSweep.sweepOnce()).isEqualTo(1);
        assertThat(expiredStore.findRequest(pending2).orElseThrow().state())
                .isEqualTo("EXPIRED");
        assertThat(expiredEvents.rows.stream().map(EventRow::type))
                .contains("APPROVAL_EXPIRED");
    }

    @Test
    void pcF06_singleUse授权_消费恰一次() {
        FakeApprovalStore store = new FakeApprovalStore();
        UUID authzId = store.insertAuthorization(UUID.randomUUID(), FIXED.instant());
        UUID operationId = UUID.randomUUID();
        assertThat(store.consumeAuthorization(authzId, operationId, FIXED.instant())).isTrue();
        assertThat(store.consumeAuthorization(authzId, UUID.randomUUID(), FIXED.instant()))
                .isFalse(); // single-use
        assertThat(store.findAuthorization(authzId).orElseThrow().state()).isEqualTo("CONSUMED");
        assertThat(store.findAuthorization(authzId).orElseThrow().operationId())
                .isEqualTo(operationId);
    }
}
