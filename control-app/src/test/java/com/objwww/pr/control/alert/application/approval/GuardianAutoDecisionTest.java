package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.application.mutation.ActionIntentStore;
import com.objwww.pr.control.alert.domain.approval.DecisionQuorum;
import com.objwww.pr.control.alert.domain.approval.GuardianVerdict;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
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

/**
 * Guardian 面单测（PE-E1，§2.3/§2.0）：封闭三值（SAFE/UNSAFE/UNCERTAIN）、权限单调
 * （R3 无放行权）、UNCERTAIN 转人工不代决、自动批准 approver=guardian 显式可见、
 * Hardline 先于一切（planner 层）。
 */
class GuardianAutoDecisionTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"),
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

    static final class DirectTx implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    static final class FakeIntents implements ActionIntentStore {
        final Map<UUID, IntentView> rows = new HashMap<>();
        final java.util.Set<UUID> planned = new java.util.HashSet<>();

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
            return planned.add(intentId);
        }
    }

    /** 复用 ApprovalFlowTest 的 fake 会有跨类耦合——这里内联最小件 */
    static final class FakeStore implements ApprovalStore {
        final Map<UUID, RequestView> requests = new HashMap<>();
        final Map<UUID, List<DecisionQuorum.Decision>> decisions = new HashMap<>();
        final Map<UUID, GrantView> grants = new HashMap<>();

        @Override
        public int generationOfRun(UUID runId) {
            return 1;
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
            RequestView v = requests.get(requestId);
            if (v == null || !expectedState.equals(v.state())) {
                return false;
            }
            requests.put(requestId, new RequestView(v.requestId(), v.intentId(), v.runId(),
                    v.actionId(), v.actionDigest(), v.observedGeneration(),
                    v.requiredApprovers(), v.scopeSnapshotHash(), v.policyVersion(),
                    nextState, v.expiresAt()));
            return true;
        }

        @Override
        public List<ExpiredRequest> expirePendingRequests(Instant now) {
            return List.of();
        }

        @Override
        public void insertGrant(GrantView g) {
            grants.put(g.grantId(), g);
        }

        @Override
        public Optional<GrantView> findActiveGrant(UUID runId, String actionDigest,
                String snapshotHash, String policyVersion) {
            return Optional.empty();
        }

        @Override
        public boolean tryReserveGrantQuota(UUID grantId, Instant at) {
            return false;
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
            return List.of();
        }

        @Override
        public UUID insertAuthorization(UUID grantId, Instant at) {
            return UUID.randomUUID();
        }

        @Override
        public boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at) {
            return true;
        }

        @Override
        public Optional<AuthzView> findAuthorization(UUID authzId) {
            return Optional.empty();
        }
    }

    private static FakeIntents intent(UUID id, String risk, String tool, String args) {
        FakeIntents intents = new FakeIntents();
        intents.rows.put(id, new ActionIntentStore.IntentView(id, UUID.randomUUID(), null,
                "a".repeat(64), tool, risk, "res://demo/checkout", "h".repeat(64), args));
        return intents;
    }

    @Test
    void peG01_封闭三值_SAFE_UNSAFE_UNCERTAIN() {
        FakeIntents intents = intent(UUID.randomUUID(), "R2", "chaos.resolve", "{\"x\":1}");
        MutationGuardian guardian = new MutationGuardian(intents, new FakeEvents(),
                new DirectTx(), Set.of("chaos.resolve"), 100, "pe-v1", FIXED);
        assertThat(guardian.evaluate(intents.rows.values().iterator().next()).verdict())
                .isEqualTo(GuardianVerdict.SAFE);

        // 参数超预算 → UNSAFE
        FakeIntents fat = intent(UUID.randomUUID(), "R2", "chaos.resolve", "x".repeat(101));
        assertThat(guardian.evaluate(fat.rows.values().iterator().next()).verdict())
                .isEqualTo(GuardianVerdict.UNSAFE);

        // 工具不在低危白名单 → UNCERTAIN 转人工
        FakeIntents stranger = intent(UUID.randomUUID(), "R2", "drop.database", "{}");
        assertThat(guardian.evaluate(stranger.rows.values().iterator().next()).verdict())
                .isEqualTo(GuardianVerdict.UNCERTAIN);
    }

    @Test
    void peG02_权限单调_R3无放行权() {
        MutationGuardian guardian = new MutationGuardian(intent(UUID.randomUUID(), "R3",
                        "chaos.resolve", "{}"), new FakeEvents(), new DirectTx(),
                Set.of("chaos.resolve"), 100, "pe-v1", FIXED);
        var review = guardian.evaluate(intent(UUID.randomUUID(), "R3", "chaos.resolve",
                "{}").rows.values().iterator().next());
        assertThat(review.verdict()).isEqualTo(GuardianVerdict.UNCERTAIN);
        assertThat(review.reason()).contains("RISK_MONOTONICITY");
    }

    @Test
    void peG03_自动决策流_SAFE自动批准_UNCERTAIN转人工() {
        UUID intentId = UUID.randomUUID();
        FakeIntents intents = intent(intentId, "R2", "chaos.resolve", "{\"x\":1}");
        FakeStore store = new FakeStore();
        FakeEvents events = new FakeEvents();
        var guardian = new MutationGuardian(intents, events, new DirectTx(),
                Set.of("chaos.resolve"), 100, "pe-v1", FIXED);
        var flow = new GuardianAutoDecisionService(guardian,
                new ApprovalRequestService(intents, store, events, new DirectTx(), "pb-prod-v1",
                        FIXED),
                new ApprovalDecisionService(store, events, new DirectTx(),
                        java.time.Duration.ofMinutes(10), FIXED));

        var outcome = flow.autoDecide(intentId);
        assertThat(outcome.verdict()).isEqualTo("SAFE");
        assertThat(outcome.approvalState()).isEqualTo("APPROVED");
        assertThat(store.grants).hasSize(1);
        // 机器审批显式可见：approver=guardian:policy，role=GUARDIAN
        var decisionRows = store.decisions.get(outcome.requestId());
        assertThat(decisionRows.get(0).approverId()).isEqualTo("guardian:pe-v1");
        assertThat(decisionRows.get(0).approverRole()).isEqualTo("GUARDIAN");
        assertThat(events.rows.stream().map(EventRow::type)).contains("GUARDIAN_REVIEWED");

        // UNCERTAIN：不铸请求不代决
        UUID humanIntent = UUID.randomUUID();
        intents.rows.put(humanIntent, new ActionIntentStore.IntentView(humanIntent,
                UUID.randomUUID(), null, "a".repeat(64), "drop.database", "R2",
                "res://demo/checkout", "h".repeat(64), "{}"));
        var human = flow.autoDecide(humanIntent);
        assertThat(human.verdict()).isEqualTo("UNCERTAIN");
        assertThat(human.approvalState()).isEqualTo("PENDING_HUMAN");
        assertThat(human.requestId()).isNull();
    }
}
