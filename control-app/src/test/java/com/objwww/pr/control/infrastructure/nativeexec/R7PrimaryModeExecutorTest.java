package com.objwww.pr.control.infrastructure.nativeexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner;
import com.objwww.pr.control.alert.application.agent.PrimaryFinalClaimProjector;
import com.objwww.pr.control.alert.application.agent.RcaActionGuard;
import com.objwww.pr.control.alert.application.agent.RcaModelGateway;
import com.objwww.pr.control.alert.application.agent.RoleRunner;
import com.objwww.pr.control.alert.application.agent.RunnerDirectory;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent;
import com.objwww.pr.control.alert.application.agent.SingleToolRoleRunner;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.claim.ClaimKind;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
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
import com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

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

/**
 * R7-X6 主模式执行链（CI 面 harness：脚本化 stub 模型零触网——R7-A0 门 CI 侧种子；
 * 真模型供应商回执关联链留 195 验收窗取证）：startPrimary 只编译主节点 →
 * {@link BoundedLlmRoleRunner} 不动点驱动（TOOL_CALL 受限直查 / DELEGATE 批中途
 * 生长子任务由 sweep 结清、唤醒续走 / FINAL 落检查点）→ 检查点提案确定性投影
 * ClaimStore → 报告相位产出带引用报告。主模式关闭（primaryProfile=null）回归旧
 * 提案路由（fail-closed 同前）。每场景附"不预灌答案"面：脚本应答不含结论文本，
 * 断言的 Claim 陈述来自脚本 FINAL、证据事实来自受控口取证。
 *
 * @author wanghua
 * @date 2026-09-11
 */
class R7PrimaryModeExecutorTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int GENERATION = 3;
    private static final String TOOL_REGISTRY_DIGEST = "r7-primary-test:metrics,logs,change";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ModelRoute ROUTE =
            new ModelRoute("route-rca", "model-rca", "ep-rca", "quota-rca", "cred-rca", null);

    private final AlertClock clock = () -> NOW;
    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final NativeInvestigationExecutorTest.TestBundles bundles =
            new NativeInvestigationExecutorTest.TestBundles();
    private final NativeInvestigationExecutorTest.TestClaims claims =
            new NativeInvestigationExecutorTest.TestClaims();
    private final NativeInvestigationExecutorTest.TestEvidence evidence =
            new NativeInvestigationExecutorTest.TestEvidence();
    private final NativeInvestigationExecutorTest.TestSnapshots snapshots =
            new NativeInvestigationExecutorTest.TestSnapshots();
    private final NativeInvestigationExecutorTest.EdgeStore edges =
            new NativeInvestigationExecutorTest.EdgeStore();
    private final ScriptedRouteClient client = new ScriptedRouteClient();
    private final PlatformLedgerFake platformLedger = new PlatformLedgerFake();
    private final SeedingToolPort toolPort = new SeedingToolPort();
    private final InMemoryRunBudgetLedger budgetLedger = new InMemoryRunBudgetLedger();

    private UUID incidentId;
    private DeterministicSupervisor supervisor;
    private RcaRunOrchestrator orchestrator;
    private NativeInvestigationExecutor executor;
    private AgentProfile primaryProfile;
    /** 子任务取证钩子（委派面 FINAL 懒入队：子证据 id 就绪后才有引用可写） */
    private Runnable onEvidenceProduced = () -> { };
    private UUID lastChildEvidenceId;

    @BeforeEach
    void setUp() {
        incidentId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId,
                "alertname=HighErrorRate|service=checkout", IncidentStatus.FIRING, 0,
                NOW, NOW, null, null, null, 0, 0, 0, null, NOW, NOW, NOW, NOW));
        primaryProfile = new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of("prometheus.query", "logs.query"),
                Map.of(BudgetKind.STEP, 4L, BudgetKind.TOKEN, 1_000_000L),
                Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                RoleRuntimeKind.BOUNDED_LLM, Set.of(), 4,
                "deterministic-final-on-exhaustion");
        supervisor = buildSupervisor(buildRegistry(true));
        executor = buildExecutor(supervisor, primaryProfile);
        orchestrator = buildOrchestrator();
    }

    // ------------------------------------------------- 主模式直查全链（A0 CI 种子）

    @Test
    @DisplayName("主模式全链：零提案段→startPrimary 单节点→TOOL_CALL 直查→FINAL→投影→带引用报告")
    void primaryModeFullChainProducesReferencedReport() {
        UUID runId = castNativeRun();
        RcaTask driver = driverOf(runId);

        client.enqueue(ok("{\"tool_call\":{\"tool_id\":\"prometheus.query\","
                + "\"args\":{\"query\":\"oa_error_rate\"}}}"));
        // FINAL 脚本懒入队：证据 id 在受控口取证时才存在（ hook 面见 SeedingToolPort）
        toolPort.afterFirstToolCall = () -> client.enqueue(ok("{\"final\":{\"claims\":["
                + "{\"claim_key\":\"c1\",\"kind\":\"ROOT_CAUSE\","
                + "\"statement\":\"checkout 错误率饱和\",\"evidence_refs\":[\""
                + toolPort.lastEvidenceId + "\"]}],\"missing_information\":[]}}"));

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(bundles.rows).as("主模式编译源=主 Profile，不依赖 native.proposal 段")
                .isEmpty();
        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        assertThat(result.artifact().orElseThrow().validationStatus())
                .isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(result.artifact().orElseThrow().rawText()).contains("NATIVE");

        assertThat(primaryTaskOf(runId).state()).as("FINAL 落检查点后主任务 DONE")
                .isEqualTo(RcaTaskState.DONE);
        assertThat(client.calls()).isEqualTo(2);
        assertThat(toolPort.tools).containsExactly("prometheus.query");
        assertThat(evidence.findByRunId(runId)).hasSize(1);

        // 投影面：检查点提案 → ClaimStore（带引用断言，来源=证据行标签）
        assertThat(claims.appended).hasSize(1);
        var claim = claims.appended.get(0);
        assertThat(claim.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(claim.kind()).isEqualTo(ClaimKind.ROOT_CAUSE);
        assertThat(claim.evidenceRefs()).containsExactly(toolPort.lastEvidenceId);
        assertThat(claim.sources()).containsExactly("prometheus");
        assertThat(claim.snapshotDigest()).isEqualTo(snapshots.frozen.get(0).snapshotDigest());
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);

        // FUT-49 共用收尾出口
        var outcome = orchestrator.finishTask(driver, "worker-a", -1, -1, result,
                newAttempt(driver));
        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.reports.all()).hasSize(1);
    }

    // ------------------------------------------------- openRun 限额并集（195 真窗回归）

    @Test
    @DisplayName("openRun 限额并集：生产形四维（无 TOKEN）下主 Profile TOKEN 维并入——195 真窗缺陷回归")
    void openRunMergesPrimaryTokenBudgetFromProfile() {
        // 生产装配面（AlertAm4Config）：executor 开局限额 = 旧四维 budget.* 键，无 TOKEN。
        // 修复前真 PG 缺行 fail-closed → 全量 BudgetExhaustedException(TOKEN 余额=0)；
        // CI 全绿根因 = 本类夹具 generousLimits() 自带 TOKEN + InMemory 缺行放行（与 PG
        // 缺行拒绝的语义分叉）。本例改用生产形限额驱动，修复后 TOKEN 行必须来自主 Profile。
        executor = buildExecutor(supervisor, primaryProfile,
                Map.of(BudgetKind.STEP, 256L, BudgetKind.TOOL_CALL, 256L,
                        BudgetKind.EVIDENCE, 256L, BudgetKind.SUBTASK, 64L));
        UUID runId = castNativeRun();
        RcaTask driver = driverOf(runId);

        client.enqueue(ok("{\"tool_call\":{\"tool_id\":\"prometheus.query\","
                + "\"args\":{\"query\":\"oa_error_rate\"}}}"));
        toolPort.afterFirstToolCall = () -> client.enqueue(ok("{\"final\":{\"claims\":["
                + "{\"claim_key\":\"c1\",\"kind\":\"ROOT_CAUSE\","
                + "\"statement\":\"checkout 错误率饱和\",\"evidence_refs\":[\""
                + toolPort.lastEvidenceId + "\"]}],\"missing_information\":[]}}"));

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });
        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);

        // TOKEN 限额行在场且 = 主 Profile 值 1_000_000（两次调用实扣 60）：超限预留必须
        // 拒绝——无行的 InMemory 旧语义会放行，此断言对修复前后有区分力。
        assertThat(budgetLedger.reserve(new ReservationKey(runId,
                        primaryTaskOf(runId).id(), UUID.randomUUID(), 999L,
                        BudgetKind.TOKEN), 1_000_000L).allowed())
                .as("TOKEN 限额已由 primaryProfile 并入（限 1_000_000，实扣 60）")
                .isFalse();
        // putIfAbsent 语义：旧四维值不覆盖——STEP 仍是 executor 侧 256 而非主 Profile 的 4。
        assertThat(budgetLedger.reserve(new ReservationKey(runId,
                        primaryTaskOf(runId).id(), UUID.randomUUID(), 1000L,
                        BudgetKind.STEP), 100L).allowed())
                .as("旧四维限额值逐字节不变（STEP=256 未被主 Profile 的 4 覆盖）")
                .isTrue();
    }

    // ------------------------------------------------- 委派批中途生长子任务

    @Test
    @DisplayName("委派批获批：sweep 转驱子任务结清→唤醒续走 FINAL（不动点收敛）")
    void delegationBatchGrowsChildMidDriveAndSweepSettlesThenWakes() {
        UUID runId = castNativeRun();
        RcaTask driver = driverOf(runId);

        client.enqueue(ok("{\"delegate\":{\"requests\":[{\"gap_id\":\"gap-1\","
                + "\"role_id\":\"logs\",\"question\":\"错误日志是否聚集\","
                + "\"input_refs\":[],\"scope\":{},\"requested_budget\":4}]}}"));
        // FINAL 脚本懒入队：子任务证据 id 在子任务取证时才存在（hook 面见 produceEvidence）
        onEvidenceProduced = () -> client.enqueue(ok("{\"final\":{\"claims\":["
                + "{\"claim_key\":\"c2\",\"kind\":\"ROOT_CAUSE\","
                + "\"statement\":\"日志证实错误聚集\",\"evidence_refs\":[\""
                + lastChildEvidenceId + "\"]}],"
                + "\"missing_information\":[\"部署时间线\"]}}"));

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        UUID primaryId = primaryTaskOf(runId).id();
        var child = stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals("DELEGATE-gap-1"))
                .findFirst().orElseThrow();
        assertThat(child.state()).as("sweep 转驱子任务至终态").isEqualTo(RcaTaskState.DONE);
        assertThat(primaryTaskOf(runId).state()).isEqualTo(RcaTaskState.DONE);
        assertThat(client.calls()).isEqualTo(2);
        PrimaryCheckpoint checkpoint =
                stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(checkpoint.batchesUsed()).isEqualTo(1);
        assertThat(checkpoint.roundId()).isEqualTo(1);
        assertThat(checkpoint.finalMissingInformation()).containsExactly("部署时间线");
        assertThat(claims.appended).hasSize(1);
        assertThat(claims.appended.get(0).sources()).as("来源=子任务证据行标签")
                .containsExactly("loki");
    }

    // ------------------------------------------------- 委派目标守卫 + 动作序单调

    @Test
    @DisplayName("委派目标=主 Agent 自身：显式拒绝 ROLE_UNKNOWN，不建子任务")
    void delegatingToPrimaryItselfIsRejected() {
        UUID runId = castNativeRun();
        supervisor.startPrimary(runId, primaryProfile, Set.of());
        UUID primaryId = primaryTaskOf(runId).id();

        DeterministicSupervisor.Adjudication adjudication =
                supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                        Map.of("delegate", Map.of("requests", List.of(Map.of(
                                "gap_id", "gap-self", "role_id", "primary",
                                "question", "自我委派", "input_refs", List.of(),
                                "scope", Map.of(), "requested_budget", 4))))));

        assertThat(adjudication.batchAccepted()).isFalse();
        assertThat(adjudication.decisions())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.status()).isEqualTo(DelegationDecision.Status.REJECTED);
                    assertThat(d.rejectReason()).isEqualTo("ROLE_UNKNOWN");
                });
        assertThat(stores.tasks.findByRunId(runId))
                .filteredOn(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .hasSize(1);
    }

    @Test
    @DisplayName("多步链动作序单调：三步模型调用预算键不重复（步进推进决策序）")
    void actionSeqMonotonicAcrossStepsSoBudgetKeysNeverCollide() {
        UUID runId = castNativeRun();
        RcaTask driver = driverOf(runId);

        client.enqueue(ok("{\"tool_call\":{\"tool_id\":\"prometheus.query\","
                + "\"args\":{\"query\":\"q1\"}}}"));
        client.enqueue(ok("{\"tool_call\":{\"tool_id\":\"logs.query\","
                + "\"args\":{\"service\":\"checkout\"}}}"));
        client.enqueue(ok("{\"final\":{\"claims\":[],\"missing_information\":[]}}"));

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        assertThat(client.calls()).isEqualTo(3);
        // 每步 reserve 保守 1_500 → commit 实扣 usage 30：3 步实扣 90。若 action_seq
        // 撞键，reserve 幂等假成功不入新账（消耗 ≠90）且 rca_model_call open 撞唯一键
        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOKEN)).isEqualTo(90L);
        var actionSeqs = stores.modelCalls.all().stream()
                .map(row -> row.open().actionSeq())
                .distinct().toList();
        assertThat(actionSeqs).as("rca_model_call.action_seq 逐步单调不重复").hasSize(3);
    }

    // ------------------------------------------------- 主模式关闭回归

    @Test
    @DisplayName("主模式关闭（primaryProfile=null）：回归旧提案路由，无提案段 fail-closed")
    void primaryDisabledFallsBackToLegacyProposalRouting() {
        NativeInvestigationExecutor legacy = buildExecutor(
                buildSupervisor(buildRegistry(false)), null);
        UUID runId = castNativeRun();
        RcaTask driver = driverOf(runId);

        RcaTaskExecutor.ExecutionResult result = legacy.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.FAILED_TERMINAL);
        assertThat(result.errorClass()).isEqualTo("PROPOSAL_MISSING");
        assertThat(stores.tasks.findByRunId(runId)).as("零 DAG 任务（未启动）").hasSize(1);
        assertThat(claims.appended).isEmpty();
    }

    // ------------------------------------------------- 投影映射

    @Test
    @DisplayName("检查点提案投影：双源 ROOT_CAUSE=TRUE/多源一致；EXCLUSION=FALSE；零引用跳过；未知 kind 缺省 HYPOTHESIS")
    void projectionMappingAndSkipRules() {
        UUID e1 = seedEvidence("prometheus");
        UUID e2 = seedEvidence("loki");
        PrimaryCheckpoint checkpoint = new PrimaryCheckpoint(UUID.randomUUID(), UUID.randomUUID(),
                0, PrimaryCheckpoint.Phase.PRIMARY_READY, 3, 2, 0, null,
                List.of(Map.of("claim_key", "c1", "kind", "ROOT_CAUSE",
                                "statement", "双源", "evidence_refs", List.of(e1, e2)),
                        Map.of("claim_key", "c2", "kind", "EXCLUSION",
                                "statement", "已排除", "evidence_refs", List.of(e1),
                                "admission_note", "note-x"),
                        Map.of("claim_key", "c3", "kind", "ROOT_CAUSE",
                                "statement", "零引用", "evidence_refs", List.of()),
                        Map.of("claim_key", "c4", "kind", "WEIRD",
                                "statement", "未知类型", "evidence_refs", List.of(e1))),
                List.of(), NOW);
        var projector = new PrimaryFinalClaimProjector(claims, evidence);

        int appended = projector.project(UUID.randomUUID(), checkpoint,
                Digest.sha256Of("snap").hex(), GENERATION, "1/2");

        assertThat(appended).isEqualTo(3);
        var rows = claims.appended;
        assertThat(rows.get(0).status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(rows.get(0).evidenceBasis())
                .isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(rows.get(1).status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(rows.get(1).evidenceBasis()).isEqualTo(EvidenceBasis.SINGLE_SOURCE);
        assertThat(rows.get(1).reason()).contains("note-x");
        assertThat(rows.get(2).kind()).isEqualTo(ClaimKind.HYPOTHESIS);
        assertThat(rows.get(2).status()).isEqualTo(ClaimStatus.UNKNOWN);
    }

    // ------------------------------------------------------------------ 夹具

    private DeterministicSupervisor buildSupervisor(AgentRegistry agents) {
        return new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, stores.bindings,
                        withoutTransaction()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents, withoutTransaction(), clock);
    }

    private NativeInvestigationExecutor buildExecutor(DeterministicSupervisor supervisor,
            AgentProfile primary) {
        return buildExecutor(supervisor, primary, generousLimits());
    }

    private NativeInvestigationExecutor buildExecutor(DeterministicSupervisor supervisor,
            AgentProfile primary, Map<BudgetKind, Long> openRunLimits) {
        AgentRegistry agents = primary == null ? buildRegistry(false) : buildRegistry(true);
        ExecutionLedger rcaSinkLedger = new ExecutionLedger(
                new RcaModelEventSink(stores.rcaEvents, new ObjectMapper()));
        ModelGateway platform = new ModelGateway(ROUTE, null, client, null, params(),
                platformLedger, new PricingService(Map.of()), rcaSinkLedger, CLOCK);
        RcaModelGateway rcaGateway = new RcaModelGateway(platform, stores.modelCalls,
                new PricingService(Map.of()), CLOCK);
        RunBudgetGate gate = new RunBudgetGate(budgetLedger);
        RcaActionGuard guard = new RcaActionGuard(stores.runs, stores.tasks, agents, gate,
                rcaGateway, CLOCK);
        BoundedLlmRoleRunner bounded = new BoundedLlmRoleRunner(guard, supervisor,
                stores.checkpoints, evidence, toolPort, MAPPER, CLOCK);
        SingleToolRoleRunner compat = new SingleToolRoleRunner(Map.of(
                "metrics", (ctx, start, end) -> produceEvidence(ctx, "prometheus"),
                "logs", (ctx, start, end) -> produceEvidence(ctx, "loki"),
                "change", (ctx, start, end) -> produceEvidence(ctx, "cmdb")));
        RunnerDirectory directory = primary == null
                ? new RunnerDirectory(List.of(compat))
                : new RunnerDirectory(List.of(compat, bounded));
        return new NativeInvestigationExecutor(bundles, supervisor, stores.tasks,
                stores.runs, evidence, snapshots,
                new com.objwww.pr.control.alert.application.agent.NativeRcaAgent(evidence,
                        snapshots, claims, new ClaimReducer(Set.of(), "r7-policy")),
                claims, new EvidencePackageValidator(65_536, 32, 4_096),
                TOOL_REGISTRY_DIGEST, clock, AlertMetrics.NOOP, gate, openRunLimits,
                stores.toolLedger, stores.bindings, agents, directory,
                stores.checkpoints, primary);
    }

    private RcaRunOrchestrator buildOrchestrator() {
        return new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls,
                new ReportCompletedNotifier(stores.publications, stores.outboxes,
                        List.of("test"), "r7-primary-test", 280),
                stores.cas, com.objwww.pr.control.alert.domain.service.SlaPolicy.defaults(),
                clock, "rca", AlertMetrics.NOOP,
                com.objwww.pr.control.release.application.CanaryRouter.holmesOnly(),
                stores.winners);
    }

    private static AgentRegistry buildRegistry(boolean withPrimary) {
        List<AgentProfile> profiles = new ArrayList<>(List.of(
                expert("metrics"), expert("logs"), expert("change")));
        if (withPrimary) {
            profiles.add(new AgentProfile("primary", "1", "prompt-primary", "pv",
                    Set.of("prometheus.query", "logs.query"),
                    Map.of(BudgetKind.STEP, 4L, BudgetKind.TOKEN, 1_000_000L),
                    Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                    RoleRuntimeKind.BOUNDED_LLM, Set.of(), 4,
                    "deterministic-final-on-exhaustion"));
        }
        return withPrimary
                ? AgentRegistry.forRelease("release-r7-test", profiles)
                : new AgentRegistry(profiles);
    }

    private static AgentProfile expert(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv", Set.of(),
                Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"), Map.of(),
                AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 1, "single-pass");
    }

    /** 兼容 handler 取证面：落证据行（runId 绑定）并记录证据 id（不预灌结论） */
    private SingleToolEvidenceAgent.AgentResult produceEvidence(
            SingleToolEvidenceAgent.CallContext ctx, String source) {
        UUID id = UUID.randomUUID();
        evidence.insert(new EvidenceEnvelope(id, ctx.runId(), ctx.taskId(), "logs.query",
                EvidenceEnvelope.SCHEMA_VERSION, ctx.observedGeneration(), source,
                Map.of("time_range", ctx.timeRange()), NOW, NOW, "{}",
                Digest.sha256Of("{}").hex()));
        lastChildEvidenceId = id;
        onEvidenceProduced.run();
        return new SingleToolEvidenceAgent.AgentResult(
                SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED, List.of(id), null);
    }

    private UUID seedEvidence(String source) {
        UUID id = UUID.randomUUID();
        evidence.insert(new EvidenceEnvelope(id, UUID.randomUUID(), UUID.randomUUID(),
                "logs.query", EvidenceEnvelope.SCHEMA_VERSION, GENERATION, source,
                Map.of("time_range", "1/2"), NOW, NOW, "{}",
                Digest.sha256Of("{}").hex()));
        return id;
    }

    private UUID castNativeRun() {
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, GENERATION,
                RunTrigger.INITIAL, RcaRunState.QUEUED, Digest.sha256Of("material"),
                NOW, NOW, null, null, null);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle-v1"), "g:checkout", 42, "BUCKETED_NATIVE"));
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), run.id(),
                RcaTask.taskKeyFor(RcaEngine.NATIVE), RcaTaskState.READY, 5, NOW, NOW,
                Instant.MAX, null, null, 0, 0, 3, NOW, NOW));
        RcaTask driver = stores.tasks.findByRunId(run.id()).get(0);
        stores.tasks.update(new RcaTask(driver.id(), driver.runId(), driver.taskKey(),
                RcaTaskState.LEASED, driver.priority(), driver.availableAt(),
                driver.readySince(), driver.deadlineAt(), "worker-a",
                NOW.plus(Duration.ofMinutes(5)), 0, 1, driver.maxAttempts(),
                driver.createdAt(), NOW));
        return run.id();
    }

    private RcaTask driverOf(UUID runId) {
        return stores.tasks.findByRunId(runId).get(0);
    }

    private RcaTask primaryTaskOf(UUID runId) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst().orElseThrow();
    }

    private RcaAttempt newAttempt(RcaTask driver) {
        return new RcaAttempt(UUID.randomUUID(), driver.id(), 1, driver.leaseEpoch() + 1,
                "worker-a", RcaAttemptStatus.STARTED, null, null, null, NOW, null, null);
    }

    private static RouteCallOutcome ok(String content) {
        return new RouteCallOutcome.Ok(content, new TokenUsage(20, 10, 30), false,
                "model-rca", "req-" + UUID.randomUUID(), Duration.ofMillis(5));
    }

    private static Map<BudgetKind, Long> generousLimits() {
        return Map.of(BudgetKind.STEP, 256L, BudgetKind.TOOL_CALL, 256L,
                BudgetKind.EVIDENCE, 256L, BudgetKind.SUBTASK, 64L,
                BudgetKind.TOKEN, 1_000_000L);
    }

    private static ModelGatewayParams params() {
        return new ModelGatewayParams(0, 4, 1_000, 1_000, 100_000,
                Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofSeconds(5),
                4, Duration.ofSeconds(10), Duration.ofMillis(1), Duration.ofMillis(5),
                "test-provider", "v1");
    }

    private static TransactionOperations withoutTransaction() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 取证口存根：记录工具调用、落本 run 证据行（FINAL 引用与投影来源推导的事实面） */
    private final class SeedingToolPort implements BoundedLlmRoleRunner.PrimaryToolPort {
        final List<String> tools = new ArrayList<>();
        String lastEvidenceId;
        Runnable afterFirstToolCall = () -> { };
        private boolean firstFired;

        @Override
        public UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
                Map<String, Object> args) {
            tools.add(toolId);
            UUID id = UUID.randomUUID();
            evidence.insert(new EvidenceEnvelope(id, ctx.runId(), ctx.taskId(), toolId,
                    EvidenceEnvelope.SCHEMA_VERSION, ctx.observedGeneration(),
                    toolId.substring(0, toolId.indexOf('.')),
                    Map.of("time_range", ctx.timeRange()), NOW, NOW, "{}",
                    Digest.sha256Of("{}").hex()));
            lastEvidenceId = id.toString();
            if (!firstFired) {
                firstFired = true;
                afterFirstToolCall.run();
            }
            return id;
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
