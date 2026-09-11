package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallContext;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.application.ModelGateway;
import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayParams;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7a-2+X5 单测（v2.1 §六固定顺序 + §五 Claim 准入）：守卫前置逐项拒绝
 * （活跃/generation/lease/deadline/角色版本获准）、TOKEN 预算三段结算
 * （成功实扣/确证未发出退款/发送后失败保守占用）、PrimaryClaimAdmission
 * 的 RD05 降级与 RX20 来源唯一。
 */
class R7ActionGuardAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelRoute ROUTE =
            new ModelRoute("route-rca", "model-rca", "ep-rca", "quota-rca", "cred-rca", null);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final InMemoryRunBudgetLedger budgetLedger = new InMemoryRunBudgetLedger();
    private final ScriptedRouteClient client = new ScriptedRouteClient();
    private final PlatformLedgerFake platformLedger = new PlatformLedgerFake();

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final String roleDigest = primaryProfile().digest();

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 3, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("m"), NOW, NOW, null, null, null));
        stores.tasks.insert(leasedTask());
        stores.runs.update(new RcaRun(runId, stores.runs.findById(runId).orElseThrow()
                .incidentId(), 3, RunTrigger.RERUN, RcaRunState.RUNNING,
                Digest.sha256Of("m"), NOW, NOW, NOW, null, null));
        ExecutionLedger rcaSink = new ExecutionLedger(
                new com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink(
                        stores.rcaEvents, new com.fasterxml.jackson.databind.ObjectMapper()));
        ModelGateway platform = new ModelGateway(ROUTE, null, client, null, params(),
                platformLedger, new PricingService(Map.of()), rcaSink, CLOCK);
        RcaModelGateway rcaGateway = new RcaModelGateway(platform, stores.modelCalls,
                new PricingService(Map.of()), CLOCK);
        RunBudgetGate gate = new RunBudgetGate(budgetLedger);
        gate.openRun(runId, Map.of(BudgetKind.TOKEN, 1_000L));
        guard = new RcaActionGuard(stores.runs, stores.tasks,
                new AgentRegistry(List.of(primaryProfile())), gate, rcaGateway, CLOCK);
    }

    private RcaActionGuard guard;

    private RcaTask leasedTask() {
        return new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE, RcaTaskState.RUNNING,
                5, NOW, NOW, Instant.MAX, "worker-1", NOW.plusSeconds(300), 7L, 1, 2,
                NOW, NOW, 0);
    }

    private RcaActionGuard.ModelAction action() {
        return new RcaActionGuard.ModelAction(runId, taskId, attemptId, 0, 0,
                "primary", "1", roleDigest, 3L, 7L, NOW.plusSeconds(60),
                null, null, null, () -> true);
    }

    // ------------------------------------------------------- §六 前置顺序

    @Test
    void run不活跃_拒绝且零预留零触网() {
        stores.runs.update(terminatedRun(RcaRunState.CANCELLED));

        assertThatThrownBy(() -> guard.guardedModelCall(action(), "p", 10, 50))
                .isInstanceOf(RcaActionGuard.RcaActionGuardReject.class)
                .hasFieldOrPropertyWithValue("code", RcaActionGuard.REJ_RUN_NOT_ACTIVE);
        assertThat(client.calls()).isZero();
        assertThat(budgetLedger.entryCount()).isZero();
    }

    @Test
    void generation漂移_拒绝() {
        assertThatThrownBy(() -> guard.guardedModelCall(withGeneration(9), "p", 10, 50))
                .isInstanceOf(RcaActionGuard.RcaActionGuardReject.class)
                .hasFieldOrPropertyWithValue("code", RcaActionGuard.REJ_GENERATION_FENCE);
    }

    @Test
    void leaseEpoch漂移_拒绝() {
        assertThatThrownBy(() -> guard.guardedModelCall(withLease(1L), "p", 10, 50))
                .isInstanceOf(RcaActionGuard.RcaActionGuardReject.class)
                .hasFieldOrPropertyWithValue("code", RcaActionGuard.REJ_LEASE_FENCE);
    }

    @Test
    void deadline已过_拒绝且不落调用记录() {
        RcaActionGuard.ModelAction late = new RcaActionGuard.ModelAction(runId, taskId,
                attemptId, 0, 0, "primary", "1", roleDigest, 3L, 7L,
                NOW.minusSeconds(1), null, null, null, () -> true);

        assertThatThrownBy(() -> guard.guardedModelCall(late, "p", 10, 50))
                .isInstanceOf(RcaActionGuard.RcaActionGuardReject.class)
                .hasFieldOrPropertyWithValue("code", RcaActionGuard.REJ_DEADLINE);
        assertThat(client.calls()).isZero();
        assertThat(stores.modelCalls.all()).as("无发送资格即无调用记录").isEmpty();
    }

    @Test
    void 角色digest漂移_冒名顶替拒绝() {
        RcaActionGuard.ModelAction bad = new RcaActionGuard.ModelAction(runId, taskId,
                attemptId, 0, 0, "primary", "1", "b".repeat(64), 3L, 7L,
                NOW.plusSeconds(60), null, null, null, () -> true);

        assertThatThrownBy(() -> guard.guardedModelCall(bad, "p", 10, 50))
                .isInstanceOf(RcaActionGuard.RcaActionGuardReject.class)
                .hasFieldOrPropertyWithValue("code", RcaActionGuard.REJ_ROLE_NOT_ADMITTED);
        assertThat(client.calls()).isZero();
    }

    // ------------------------------------------------------- 预算三段结算

    @Test
    void 成功_TOKEN实扣_RCA账本SUCCESS() {
        client.enqueue(new RouteCallOutcome.Ok("ok", new TokenUsage(20, 10, 30),
                false, "model-rca", "req-1", Duration.ofMillis(5)));

        RcaModelOutcome outcome = guard.guardedModelCall(action(), "p", 10, 50);

        assertThat(outcome.totalTokens()).isEqualTo(30);
        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOKEN)).isEqualTo(30);
        assertThat(budgetLedger.stateOf(key())).isEqualTo("COMMITTED");
        assertThat(stores.modelCalls.all().get(0).state()).isEqualTo("SUCCESS");
    }

    @Test
    void 确证未发出_全维退款() {
        // 预占同 (run,task,attempt,action,physical) 行 → RCA 账本 open 撞唯一键
        // = 发送资格未取得即失败（零触网确证未发出）→ releaseOn 命中全额退款
        stores.modelCalls.open(new RcaModelCallLedger.OpenRow(UUID.randomUUID(), runId,
                taskId, attemptId, 0, 1, 0, "primary", "1", roleDigest, "pd",
                null, null, null, null, 3L));

        assertThatThrownBy(() -> guard.guardedModelCall(action(), "p", 10, 50))
                .isInstanceOf(RcaModelCallException.class);

        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOKEN))
                .as("确证未发出 → release 全额退款").isZero();
        assertThat(stores.modelCalls.all()).as("仅预占行，无新增调用行").hasSize(1);
    }

    @Test
    void 平台账本不可写_UNKNOWN_provisional保守占用() {
        // 平台 D5 闸拒绝（insertStarted 失败）：请求是否已发出不确定 → UNKNOWN
        // 保守占预算（provisional），对账面经 invocation_id 关联，不盲重发
        platformLedger.failInsertStarted = true;

        assertThatThrownBy(() -> guard.guardedModelCall(action(), "p", 10, 50))
                .isInstanceOf(RcaModelCallException.class);

        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOKEN))
                .as("不确定 → provisional 保守占用").isEqualTo(50L);
        assertThat(stores.modelCalls.all().get(0).state()).isEqualTo("UNKNOWN");
    }

    @Test
    void 发送后失败_provisional保守占用() {
        client.enqueue(new RouteCallOutcome.Failed(
                new ModelCallFailure.RequestInvalid(FaultScope.MODEL), 400, null, null,
                Duration.ofMillis(2)));

        assertThatThrownBy(() -> guard.guardedModelCall(action(), "p", 10, 50))
                .isInstanceOf(RcaModelCallException.class);

        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOKEN))
                .as("保守占用（是否已执行不确定）").isEqualTo(50L);
        assertThat(stores.modelCalls.all().get(0).state()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------- X5 Claim 准入

    @Test
    void claim准入_ROOT_CAUSE无有效引用降级_越界引用剥离_RD05() {
        PrimaryDecision.FinalClaim noEvidence = new PrimaryDecision.FinalClaim("c1",
                "ROOT_CAUSE", "支付服务因 X 崩溃", List.of());
        PrimaryDecision.FinalClaim crossRun = new PrimaryDecision.FinalClaim("c2",
                "ROOT_CAUSE", "订单重复由部署引起",
                List.of("not-a-ref", "6c3c6c3c-0000-0000-0000-000000000000"));

        PrimaryClaimAdmission.AdmissionResult r = PrimaryClaimAdmission.admit(
                List.of(noEvidence, crossRun), Set.of());

        assertThat(r.claims().get(0).kind()).as("零有效引用不确认根因")
                .isEqualTo("HYPOTHESIS");
        assertThat(r.claims().get(0).admissionNote())
                .contains(PrimaryClaimAdmission.NOTE_DOWNGRADED_NO_EVIDENCE);
        assertThat(r.claims().get(1).evidenceRefs()).as("越界引用全剥离").isEmpty();
        assertThat(r.strippedRefs()).isEqualTo(2);
    }

    @Test
    void claim准入_本run引用保留_重复引用算一份_RX20() {
        UUID evidenceId = UUID.randomUUID();
        PrimaryDecision.FinalClaim good = new PrimaryDecision.FinalClaim("c1", "ROOT_CAUSE",
                "异常由部署触发", List.of(evidenceId.toString(), evidenceId.toString()));

        PrimaryClaimAdmission.AdmissionResult r = PrimaryClaimAdmission.admit(
                List.of(good), Set.of(evidenceId.toString()));

        assertThat(r.claims().get(0).kind()).isEqualTo("ROOT_CAUSE");
        assertThat(r.claims().get(0).evidenceRefs()).as("同一来源去重算一份")
                .containsExactly(evidenceId.toString());
        assertThat(r.downgraded()).isZero();
    }

    // ------------------------------------------------------- 夹具

    private RcaActionGuard.ModelAction withGeneration(long generation) {
        return new RcaActionGuard.ModelAction(runId, taskId, attemptId, 0, 0,
                "primary", "1", roleDigest, generation, 7L, NOW.plusSeconds(60),
                null, null, null, () -> true);
    }

    private RcaActionGuard.ModelAction withLease(long epoch) {
        return new RcaActionGuard.ModelAction(runId, taskId, attemptId, 0, 0,
                "primary", "1", roleDigest, 3L, epoch, NOW.plusSeconds(60),
                null, null, null, () -> true);
    }

    private com.objwww.pr.control.alert.domain.budget.ReservationKey key() {
        return new com.objwww.pr.control.alert.domain.budget.ReservationKey(runId,
                taskId, attemptId, 0, BudgetKind.TOKEN);
    }

    private RcaRun terminatedRun(RcaRunState state) {
        RcaRun current = stores.runs.findById(runId).orElseThrow();
        return new RcaRun(current.id(), current.incidentId(), current.generation(),
                current.trigger(), state, current.investigationHash(), current.createdAt(),
                NOW, current.startedAt(), NOW, "TEST");
    }

    private static AgentProfile primaryProfile() {
        return new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of(), Map.of(), Map.of("type", "object"), Map.of(),
                AgentPhase.PRIMARY, RoleRuntimeKind.BOUNDED_LLM, Set.of(), 8, "single-pass");
    }

    private static ModelGatewayParams params() {
        return new ModelGatewayParams(0, 4, 1_000, 1_000, 100_000,
                Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofSeconds(5),
                4, Duration.ofSeconds(10), Duration.ofMillis(1), Duration.ofMillis(5),
                "test-provider", "v1");
    }

    private static final class ScriptedRouteClient implements RouteClientPort {
        private final Queue<RouteCallOutcome> script = new ArrayDeque<>();
        private int calls;

        void enqueue(RouteCallOutcome outcome) {
            script.add(outcome);
        }

        int calls() {
            return calls;
        }

        @Override
        public RouteCallOutcome complete(ModelRequest request, Duration timeout) {
            calls++;
            return script.poll();
        }
    }

    private static final class PlatformLedgerFake implements ModelCallLedgerRepository {
        final List<ModelCallLedgerEntry> rows = new ArrayList<>();
        boolean failInsertStarted;

        @Override
        public void insertStarted(ModelCallLedgerEntry entry) {
            if (failInsertStarted) {
                throw new IllegalStateException("模拟平台账本写失败");
            }
            rows.add(entry);
        }

        @Override
        public boolean completeTerminalSuccess(UUID id, TokenUsage usage,
                boolean usageMissing, String reportedModel, String providerRequestId,
                Duration latency, Long costMicros, String pricingVersion, String currency,
                Long inputPriceMicrosPerK, Long outputPriceMicrosPerK) {
            return true;
        }

        @Override
        public boolean completeTerminalFailure(UUID id, String outcome, Integer httpStatus,
                Duration retryAfter, Duration latency, String errorCode,
                String errorFingerprint, String sanitizedMessage) {
            return true;
        }

        @Override
        public int markUnknownOlderThan(Instant threshold) {
            return 0;
        }
    }
}
