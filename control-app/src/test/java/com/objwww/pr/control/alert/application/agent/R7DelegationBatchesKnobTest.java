package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.application.ModelGateway;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 臂A 前置债清偿单测（R7：max_delegation_batches 旋钮化，假件面零真网）：
 * ① 缺省=2 行为与旋钮化前一致（两批获批、第三批预算拒、信封余量随 batchesUsed 收敛）；
 * ② 旋钮=0（臂A 零委派姿态）：DELEGATE 决策确定性全拒（DELEGATION_BUDGET_EXHAUSTED）、
 *    零子任务零状态迁移、信封 delegation_batches_remaining=0；
 * ③ 旋钮=1：首批获批、第二批预算拒；
 * ④ 负数旋钮构造期 fail-fast。
 * 信封余量与裁决上限同源断言：runner 信封读 supervisor 注入值（非各自读配置）。
 */
class R7DelegationBatchesKnobTest {

    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelRoute ROUTE =
            new ModelRoute("route-rca", "model-rca", "ep-rca", "quota-rca", "cred-rca", null);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final EdgeStore edges = new EdgeStore();
    private final ScriptedRouteClient client = new ScriptedRouteClient();
    private final PlatformLedgerFake platformLedger = new PlatformLedgerFake();
    private final StaticEvidence evidence = new StaticEvidence();
    private final ToolPortStub toolPort = new ToolPortStub();
    private final UUID runId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    private final AgentRegistry agents = new AgentRegistry(List.of(
            primaryProfile(), expertProfile("metrics-expert"), expertProfile("logs-expert")));

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null,
                null, null));
    }

    /** 指定旋钮值装配 supervisor + 绑定同一 supervisor 的 runner（信封同源面） */
    private DeterministicSupervisor supervisor(int maxDelegationBatches) {
        return new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents, inPlaceTx(), () -> NOW,
                maxDelegationBatches);
    }

    private BoundedLlmRoleRunner runnerOf(DeterministicSupervisor supervisor) {
        ExecutionLedger rcaSinkLedger = new ExecutionLedger(
                new com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink(
                        stores.rcaEvents, new ObjectMapper()));
        ModelGateway platform = new ModelGateway(ROUTE, null, client, null,
                params(), platformLedger, new PricingService(Map.of()), rcaSinkLedger, CLOCK);
        RcaModelGateway rcaGateway = new RcaModelGateway(platform, stores.modelCalls,
                new PricingService(Map.of()), CLOCK);
        RunBudgetGate gate = new RunBudgetGate(new InMemoryRunBudgetLedger());
        gate.openRun(runId, Map.of(BudgetKind.TOKEN, 1_000_000L));
        RcaActionGuard guard = new RcaActionGuard(stores.runs, stores.tasks, agents,
                gate, rcaGateway, CLOCK);
        return new BoundedLlmRoleRunner(guard, supervisor, stores.checkpoints,
                evidence, toolPort, MAPPER, CLOCK);
    }

    // ------------------------------------------------- ① 缺省=2 行为不变

    @Test
    void 缺省旋钮2_两批获批第三批预算拒_信封余量随消耗收敛() {
        DeterministicSupervisor supervisor = supervisor(
                DeterministicSupervisor.MAX_DELEGATION_BATCHES);
        assertThat(supervisor.maxDelegationBatches()).isEqualTo(2);
        UUID primaryId = startPrimary(supervisor);

        // 信封余量（同源面）：零消耗时 = 旋钮值 2
        assertThat(envelopeOf(supervisor, primaryId))
                .contains("\"delegation_batches_remaining\":2");

        for (int batch = 0; batch < 2; batch++) {
            DeterministicSupervisor.Adjudication accepted = supervisor.adjudicateDelegation(
                    runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                            "requests", List.of(request("gap-" + batch, "metrics-expert"))))));
            assertThat(accepted.batchAccepted()).isTrue();
            finishChildren(batch + 1);
            supervisor.wakePrimary(runId, primaryId);
        }

        DeterministicSupervisor.Adjudication third = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(request("gap-3", "metrics-expert"))))));
        assertThat(third.batchAccepted()).isFalse();
        assertThat(third.decisions()).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DelegationDecision.Status.REJECTED);
            assertThat(d.rejectReason())
                    .isEqualTo(DeterministicSupervisor.REJ_DELEGATION_BUDGET_EXHAUSTED);
        });
        // 余量耗尽后信封同为 0（裁决与信封读同一注入值）
        assertThat(envelopeOf(supervisor, primaryId))
                .contains("\"delegation_batches_remaining\":0");
    }

    // ------------------------------------------------- ② 旋钮=0 臂A 零委派姿态

    @Test
    void 旋钮0_委派确定性全拒_零子任务状态不动_信封余量0() {
        DeterministicSupervisor supervisor = supervisor(0);
        UUID primaryId = startPrimary(supervisor);

        assertThat(envelopeOf(supervisor, primaryId))
                .as("臂A 姿态信封余量恒 0")
                .contains("\"delegation_batches_remaining\":0");

        DeterministicSupervisor.Adjudication result = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(
                                request("gap-1", "metrics-expert"),
                                request("gap-2", "logs-expert"))))));

        assertThat(result.batchAccepted()).isFalse();
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DelegationDecision.Status.REJECTED);
            assertThat(d.rejectReason())
                    .isEqualTo(DeterministicSupervisor.REJ_DELEGATION_BUDGET_EXHAUSTED);
        });
        assertThat(stores.tasks.findByRunId(runId)).as("零委派=零子任务").hasSize(1);
        PrimaryCheckpoint cp = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(cp.phase()).as("全拒状态不动").isEqualTo(
                PrimaryCheckpoint.Phase.PRIMARY_READY);
        assertThat(cp.batchesUsed()).isZero();
    }

    // ------------------------------------------------- ③ 旋钮=1 第二批拒

    @Test
    void 旋钮1_首批获批第二批预算拒() {
        DeterministicSupervisor supervisor = supervisor(1);
        UUID primaryId = startPrimary(supervisor);

        DeterministicSupervisor.Adjudication first = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(request("gap-1", "metrics-expert"))))));
        assertThat(first.batchAccepted()).isTrue();
        assertThat(stores.checkpoints.findByTask(primaryId).orElseThrow().batchesUsed())
                .isEqualTo(1);

        finishChildren(1);
        supervisor.wakePrimary(runId, primaryId);

        DeterministicSupervisor.Adjudication second = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(request("gap-2", "logs-expert"))))));
        assertThat(second.batchAccepted()).isFalse();
        assertThat(second.decisions().get(0).rejectReason())
                .isEqualTo(DeterministicSupervisor.REJ_DELEGATION_BUDGET_EXHAUSTED);
    }

    // ------------------------------------------------- ④ 负数 fail-fast

    @Test
    void 负数旋钮_构造期failFast() {
        assertThatThrownBy(() -> supervisor(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelegationBatches");
    }

    // ------------------------------------------------- 夹具

    private UUID startPrimary(DeterministicSupervisor supervisor) {
        supervisor.startPrimary(runId, primaryProfile(), Set.of());
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst().orElseThrow().id();
    }

    /** 驱动一步（脚本 FINAL）捕获模型信封原文（delegation_batches_remaining 断言面） */
    private String envelopeOf(DeterministicSupervisor supervisor, UUID primaryId) {
        BoundedLlmRoleRunner runner = runnerOf(supervisor);
        client.enqueue(new RouteCallOutcome.Ok(
                "{\"final\":{\"claims\":[],\"missing_information\":[\"x\"]}}",
                new TokenUsage(5, 0, 5), false, "model-rca", "req-env",
                Duration.ofMillis(5)));
        RcaTask task = stores.tasks.findById(primaryId).orElseThrow();
        TaskExecutionBinding binding = stores.bindings.findByTask(primaryId).orElseThrow();
        runner.drive(new RoleRunner.RoleDriveRequest(task, binding, primaryProfile(),
                new SingleToolEvidenceAgent.CallContext(runId, UUID.randomUUID(),
                        attemptId, 0, 0, null, "1757574000/1757577600"),
                "1757574000", "1757577600"));
        assertThat(client.prompts).as("信封已随模型调用下发").isNotEmpty();
        return client.prompts.get(client.prompts.size() - 1);
    }

    private void finishChildren(int round) {
        for (RcaTask child : stores.tasks.findByRunId(runId)) {
            if (!child.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE)
                    && child.roundId() == round) {
                stores.tasks.transitionState(child.id(), child.state(), RcaTaskState.DONE);
            }
        }
    }

    private static Map<String, Object> request(String gapId, String roleId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("gap_id", gapId);
        req.put("role_id", roleId);
        req.put("question", "q-" + gapId);
        req.put("input_refs", List.of());
        req.put("scope", Map.of());
        req.put("requested_budget", 4);
        return req;
    }

    private static AgentProfile primaryProfile() {
        return new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of(), Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"),
                Map.of(), AgentPhase.PRIMARY, RoleRuntimeKind.BOUNDED_LLM,
                Set.of(), 8, "single-pass");
    }

    private static AgentProfile expertProfile(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 4L), Map.of("type", "object"),
                Map.of(), AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 1, "single-pass");
    }

    private static ModelGatewayParams params() {
        return new ModelGatewayParams(
                0, 4, 1_000, 1_000, 100_000,
                Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofSeconds(5),
                4, Duration.ofSeconds(10), Duration.ofMillis(1), Duration.ofMillis(5),
                "test-provider", "v1");
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件 */
    private static final class EdgeStore implements TaskEdgeRepository {
        final List<TaskEdge> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            rows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(),
                    dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return rows.stream().toList();
        }
    }

    /** 证据仓空实现（信封 valid_artifact_refs 面只读） */
    private static final class StaticEvidence implements EvidenceRepository {
        @Override
        public void insert(EvidenceEnvelope envelope) {
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
            return Optional.empty();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return List.of();
        }
    }

    /** 受控口存根（本类用例不触发 TOOL_CALL 分支） */
    private static final class ToolPortStub implements BoundedLlmRoleRunner.PrimaryToolPort {
        @Override
        public UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
                Map<String, Object> args) {
            return UUID.randomUUID();
        }
    }

    /** 脚本化路由客户端（零真网；prompts 为信封断言面） */
    private static final class ScriptedRouteClient implements RouteClientPort {
        private final Queue<RouteCallOutcome> script = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        void enqueue(RouteCallOutcome outcome) {
            script.add(outcome);
        }

        @Override
        public RouteCallOutcome complete(ModelRequest request, Duration timeout) {
            prompts.add(request.prompt());
            return script.poll();
        }
    }

    /** 平台账本假件 */
    private static final class PlatformLedgerFake implements ModelCallLedgerRepository {
        final List<ModelCallLedgerEntry> rows = new ArrayList<>();

        @Override
        public void insertStarted(ModelCallLedgerEntry entry) {
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
