package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
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
 * R7-X2 单测（v2.1 §十一.1，假件面零真网）：运行器目录按 runtime_kind 解析——
 * <b>第四个同运行器角色纯配置可执行的证明</b>（RX01：新增 Profile 即被执行，零新
 * Java 分派）；未部署运行器类型/未知角色显式拒绝（RX03，CAPABILITY_UNAVAILABLE）；
 * BoundedLlmRoleRunner 单步语义（等待零触网/TOOL_CALL 越权计步/FINAL 准入落检查点/
 * 步数耗尽确定性兜底 FINAL 零模型调用/不可解析计步重驱）。
 */
class R7RoleRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
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
    private final AlertClockStub clock = new AlertClockStub();
    private final UUID runId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    /** 注册表含第四个同运行器角色（纯配置；RX01 证明主体） */
    private final AgentRegistry agents = new AgentRegistry(List.of(
            primaryProfile(), expertProfile("metrics-expert"), expertProfile("logs-expert"),
            fourthRoleProfile()));

    private DeterministicSupervisor supervisor;
    private BoundedLlmRoleRunner boundedRunner;
    private RunnerDirectory directory;
    private SingleToolRoleRunner singleToolRunner;

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null,
                null, null));
        supervisor = new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents, inPlaceTx(), clock);

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

        boundedRunner = new BoundedLlmRoleRunner(guard, supervisor, stores.checkpoints,
                evidence, toolPort, MAPPER, CLOCK);
        singleToolRunner = new SingleToolRoleRunner(Map.of("metrics-expert",
                (ctx, start, end) -> null));
        directory = new RunnerDirectory(List.of(boundedRunner, singleToolRunner));
    }

    // ------------------------------------------------- RX01 第四角色纯配置可执行

    @Test
    void rx01第四个同运行器角色_纯配置即被目录解析并执行() {
        AgentProfile fourth = fourthRoleProfile();
        // ① 注册表获准（同一份配置：requireExact 精确三元组）
        assertThat(agents.requireExact(fourth.name(), fourth.version(), fourth.digest()))
                .isEqualTo(fourth);
        // ② 目录按 runtime_kind 解析到同一个 BoundedLlmRoleRunner 实例（零新 Java 分派）
        assertThat(directory.requireFor(fourth)).isSameAs(boundedRunner);

        // ③ 绑定第四角色的主任务可执行：脚本 TOOL_CALL → 受控口取证 → 计步
        UUID taskId = seedBoundPrimaryTask(fourth);
        seedCheckpoint(taskId, 0);
        client.enqueue(new RouteCallOutcome.Ok(
                "{\"tool_call\":{\"tool_id\":\"code.read\",\"args\":{\"path\":\"a.java\"}}}",
                new TokenUsage(20, 10, 30), false, "model-rca", "req-1",
                Duration.ofMillis(5)));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(request(taskId, fourth));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED);
        assertThat(toolPort.invocations).hasSize(1);
        assertThat(toolPort.invocations.get(0).toolId()).isEqualTo("code.read");
        assertThat(stores.checkpoints.findByTask(taskId).orElseThrow().stepsUsed())
                .isEqualTo(1);
        assertThat(client.calls()).isEqualTo(1);
    }

    // ------------------------------------------------- RX03 未知运行器/角色拒绝

    @Test
    void rx03未部署运行器类型_目录显式拒绝_零触网() {
        AgentProfile ghost = new AgentProfile("ghost", "1", "prompt-ghost", "pv",
                Set.of(), Map.of(), Map.of("type", "object"), Map.of(),
                AgentPhase.INVESTIGATE, "not-deployed-kind", Set.of(), 1, "single-pass");

        assertThatThrownBy(() -> directory.requireFor(ghost))
                .isInstanceOf(RunnerDirectory.CapabilityUnavailableException.class)
                .hasMessageContaining("CAPABILITY_UNAVAILABLE");
        assertThat(client.calls()).as("准入期拒绝零触网").isZero();
        assertThat(directory.deployedKinds()).containsExactlyInAnyOrder(
                RoleRuntimeKind.BOUNDED_LLM, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL);
    }

    @Test
    void 绑定角色无兼容适配_单工具运行器显式拒绝() {
        SingleToolRoleRunner narrow = new SingleToolRoleRunner(Map.of("metrics-expert",
                (ctx, start, end) -> null));
        AgentProfile known = expertProfile("logs-expert");
        TaskExecutionBinding binding = new TaskExecutionBinding(UUID.randomUUID(), runId,
                0, "investigate-logs", "ghost-role", "1",
                Digest.sha256Of("ghost").hex(), null, null, List.of(), Map.of(), null,
                true, TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
        RoleRunner.RoleDriveRequest request = new RoleRunner.RoleDriveRequest(
                leasedTask(UUID.randomUUID()), binding, known, callCtx(), "1", "2");

        assertThatThrownBy(() -> narrow.drive(request))
                .isInstanceOf(RunnerDirectory.CapabilityUnavailableException.class)
                .hasMessageContaining("ghost-role");
    }

    // ------------------------------------------------- BoundedLlm 单步语义

    @Test
    void waitingChildren子任务未收官_零模型调用等待() {
        UUID primaryId = startPrimary();
        supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-1", "metrics-expert"))))));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.WAITING_CHILDREN);
        assertThat(client.calls()).as("等待面零模型调用").isZero();
    }

    @Test
    void wake后继续_TOOL_CALL取证_计步与账面SUCCESS() {
        UUID primaryId = startPrimary();
        supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-1", "metrics-expert"))))));
        finishChildren(1);
        supervisor.wakePrimary(runId, primaryId);
        client.enqueue(new RouteCallOutcome.Ok(
                "{\"tool_call\":{\"tool_id\":\"logs.query\",\"args\":{\"q\":\"err\"}}}",
                new TokenUsage(20, 10, 30), false, "model-rca", "req-2",
                Duration.ofMillis(5)));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED);
        assertThat(toolPort.invocations).hasSize(1);
        PrimaryCheckpoint checkpoint = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(checkpoint.stepsUsed()).isEqualTo(1);
        assertThat(stores.modelCalls.all().get(0).state()).isEqualTo("SUCCESS");
    }

    @Test
    void toolCall越权_allowlist外工具_计步拒绝不触口() {
        UUID primaryId = startPrimary();
        client.enqueue(new RouteCallOutcome.Ok(
                "{\"tool_call\":{\"tool_id\":\"code.search\",\"args\":{}}}",
                new TokenUsage(20, 10, 30), false, "model-rca", "req-3",
                Duration.ofMillis(5)));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.FAILED);
        assertThat(result.reason()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(toolPort.invocations).as("越权工具不触受控口").isEmpty();
        assertThat(stores.checkpoints.findByTask(primaryId).orElseThrow().stepsUsed())
                .isEqualTo(1);
    }

    @Test
    void final提案_代码准入降级与越界剥离落检查点() {
        UUID primaryId = startPrimary();
        UUID evidenceId = evidence.seed();
        client.enqueue(new RouteCallOutcome.Ok(
                "{\"final\":{\"claims\":["
                        + "{\"claim_key\":\"c1\",\"kind\":\"ROOT_CAUSE\",\"statement\":\"无引用断言\",\"evidence_refs\":[]},"
                        + "{\"claim_key\":\"c2\",\"kind\":\"ROOT_CAUSE\",\"statement\":\"有本run引用\",\"evidence_refs\":[\""
                        + evidenceId + "\",\"6c3c6c3c-0000-0000-0000-000000000000\"]}],"
                        + "\"missing_information\":[\"部署时间线\"]}}",
                new TokenUsage(20, 10, 30), false, "model-rca", "req-4",
                Duration.ofMillis(5)));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.FINAL_READY);
        PrimaryCheckpoint checkpoint = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(checkpoint.finalClaims()).hasSize(2);
        assertThat(checkpoint.finalClaims().get(0))
                .as("零有效引用 ROOT_CAUSE 降级 HYPOTHESIS（RD05）")
                .containsEntry("kind", "HYPOTHESIS")
                .containsEntry("admission_note",
                        PrimaryClaimAdmission.NOTE_DOWNGRADED_NO_EVIDENCE);
        assertThat((List<Object>) checkpoint.finalClaims().get(1).get("evidence_refs"))
                .as("越界引用剥离，只留本 run 证据")
                .containsExactly(evidenceId.toString());
        assertThat(checkpoint.finalMissingInformation()).containsExactly("部署时间线");
    }

    @Test
    void steps耗尽_确定性兜底FINAL_零模型调用() {
        UUID primaryId = startPrimary();
        // steps 已耗尽（maxSteps=1，已用 1）：重驱不花模型调用直接兜底
        stores.checkpoints.upsert(
                stores.checkpoints.findByTask(primaryId).orElseThrow().withStepAdvanced(
                        null, NOW));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.FINAL_READY);
        assertThat(result.reason()).isEqualTo("STEPS_EXHAUSTED");
        assertThat(client.calls()).as("§四 终止兜底零模型调用").isZero();
        PrimaryCheckpoint checkpoint = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(checkpoint.finalClaims()).isEmpty();
        assertThat(checkpoint.finalMissingInformation().get(0)).contains("STEPS_EXHAUSTED");
    }

    @Test
    void decision不可解析_计步重驱不静默重放() {
        UUID primaryId = startPrimary();
        client.enqueue(new RouteCallOutcome.Ok("这不是JSON决策",
                new TokenUsage(5, 0, 5), false, "model-rca", "req-5",
                Duration.ofMillis(5)));

        RoleRunner.RoleDriveResult result = boundedRunner.drive(
                request(primaryId, primaryProfile()));

        assertThat(result.outcome()).isEqualTo(RoleRunner.RoleDriveOutcome.FAILED);
        assertThat(result.reason()).isEqualTo("DECISION_UNPARSEABLE");
        assertThat(stores.checkpoints.findByTask(primaryId).orElseThrow().stepsUsed())
                .isEqualTo(1);
    }

    // ------------------------------------------------- 夹具

    /** 主模式启动（PlanCompiler 编译事务写绑定+检查点），返回主任务 id */
    private UUID startPrimary() {
        supervisor.startPrimary(runId, primaryProfile(), Set.of());
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst().orElseThrow().id();
    }

    /** RX01 面：绕过 startPrimary 手工播种第四角色的任务+绑定+检查点（绑定即配置） */
    private UUID seedBoundPrimaryTask(AgentProfile profile) {
        UUID taskId = UUID.randomUUID();
        stores.tasks.insert(new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0));
        stores.bindings.insert(new TaskExecutionBinding(taskId, runId, 0,
                RcaTask.PRIMARY_INVESTIGATE, profile.name(), profile.version(),
                profile.digest(), null, null, List.of(), profile.outputSchema(), null,
                true, TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW));
        return taskId;
    }

    private void seedCheckpoint(UUID taskId, int round) {
        stores.checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, round, NOW));
    }

    private RoleRunner.RoleDriveRequest request(UUID taskId, AgentProfile profile) {
        RcaTask task = stores.tasks.findById(taskId).orElseThrow();
        TaskExecutionBinding binding = stores.bindings.findByTask(taskId).orElseThrow();
        return new RoleRunner.RoleDriveRequest(task, binding, profile, callCtx(),
                "1757574000", "1757577600");
    }

    private SingleToolEvidenceAgent.CallContext callCtx() {
        return new SingleToolEvidenceAgent.CallContext(runId, UUID.randomUUID(),
                attemptId, 0, 0, null, "1757574000/1757577600");
    }

    private RcaTask leasedTask(UUID taskId) {
        return new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE, RcaTaskState.READY,
                5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2, NOW, NOW, 0);
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
        // maxSteps=1：steps 耗尽兜底面单测可触发（§四建议 8 为待评测上限，非契约值）
        return new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of("logs.query", "code.read"), Map.of(BudgetKind.STEP, 8L),
                Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                RoleRuntimeKind.BOUNDED_LLM, Set.of(), 1, "single-pass");
    }

    private static AgentProfile expertProfile(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 4L), Map.of("type", "object"),
                Map.of(), AgentPhase.INVESTIGATE,
                RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL, Set.of(), 1, "single-pass");
    }

    /** 第四个同运行器角色（RX01）：只新增配置（Profile），无任何新 Java 分派 */
    private static AgentProfile fourthRoleProfile() {
        return new AgentProfile("code-searcher", "1", "prompt-code", "pv",
                Set.of("code.read"), Map.of(BudgetKind.STEP, 4L),
                Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                RoleRuntimeKind.BOUNDED_LLM, Set.of(), 4, "single-pass");
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

    private static final class AlertClockStub implements com.objwww.pr.control.alert.application.AlertClock {
        @Override
        public Instant now() {
            return NOW;
        }
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

    /** 固定单行证据仓（FINAL 准入 validRefs 面） */
    private static final class StaticEvidence implements EvidenceRepository {
        private final List<EvidenceEnvelope> rows = new ArrayList<>();

        UUID seed() {
            UUID id = UUID.randomUUID();
            rows.add(new EvidenceEnvelope(id, UUID.randomUUID(), UUID.randomUUID(),
                    "logs.query", EvidenceEnvelope.SCHEMA_VERSION, 0, "loki",
                    Map.of("time_range", "1/2"), NOW, NOW, "{}",
                    Digest.sha256Of("{}").hex()));
            return id;
        }

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
            return rows.stream().filter(e -> e.evidenceId().equals(evidenceId))
                    .findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().toList();
        }
    }

    /** 受控口存根：记录越权面与取证调用 */
    private static final class ToolPortStub implements BoundedLlmRoleRunner.PrimaryToolPort {
        record Invocation(UUID taskId, String toolId, Map<String, Object> args) {
        }

        final List<Invocation> invocations = new ArrayList<>();

        @Override
        public UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
                Map<String, Object> args) {
            invocations.add(new Invocation(ctx.taskId(), toolId, args));
            return UUID.randomUUID();
        }
    }

    /** 脚本化路由客户端（零真网） */
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
