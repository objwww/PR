package com.objwww.pr.control.infrastructure.nativeexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.IncidentProjector;
import com.objwww.pr.control.alert.application.NativeReportAdapter;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotBuilder;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
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
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NativeInvestigationExecutor UT（M6-01 落点 5，驱动模型）：worker 领取
 * NATIVE_INVESTIGATE driver task 后由本执行器驱动 Native 全链——提案读 Run 固定
 * 路由 digest 的 bundle {@code native.proposal} 段（EN-03 准入固定：drive 期不读
 * active，指针移动不改在跑 Run 的提案源 S11；缺失 fail-closed，非法经 Supervisor
 * PROPOSAL_REJECTED run FAILED）→ DAG 任务逐个驱动（回放未命中 = 缺源降级 DEAD
 * 续跑，AM4 语义）→ 冻结证据快照（configDigest=路由 bundle digest）→ advance 入
 * REPORTING → NativeRcaAgent → ReportAssembler → NativeReportAdapter → 复用
 * finishTask 收尾链落报告/发布。回放 MISS 的三 Agent 全部降级不阻断报告——
 * 断言注记证据（预置，注记生产者归后续批次）驱动 Claim 面。
 */
class NativeInvestigationExecutorTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final int GENERATION = 3;
    private static final String POLICY_VERSION = "m6-policy";
    private static final String TOOL_REGISTRY_DIGEST = "nativeexec-test:metrics,logs,change";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AlertClock clock = () -> NOW;
    private AlertInMemoryStores stores;
    private TestBundles bundles;
    private TestClaims claims;
    private TestEvidence evidence;
    private TestSnapshots snapshots;
    private NativeInvestigationExecutor executor;
    private RcaRunOrchestrator orchestrator;
    private UUID incidentId;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        bundles = new TestBundles();
        claims = new TestClaims();
        evidence = new TestEvidence();
        snapshots = new TestSnapshots();
        ClaimReducer reducer = new ClaimReducer(Set.of(), POLICY_VERSION);

        AgentReplayRunner runner = new AgentReplayRunner(
                new ReplayToolGateway(replayRegistry(), new TestReplayStore()));
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, stores.toolLedger, MAPPER);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, stores.toolLedger, MAPPER);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, stores.toolLedger, MAPPER);
        NativeRcaAgent nativeRcaAgent = new NativeRcaAgent(evidence, snapshots, claims,
                reducer);

        DeterministicSupervisor supervisor = new DeterministicSupervisor(
                new PlanCompiler(agentRegistry(), stores.tasks, new EdgeStore(),
                        withoutTransaction()),
                new com.objwww.pr.control.alert.application.DagExecutionService(new EdgeStore(),
                        stores.tasks),
                stores.runs, stores.tasks, withoutTransaction(), clock);
        executor = new NativeInvestigationExecutor(bundles, supervisor, stores.tasks,
                stores.runs, evidence, snapshots, metrics, logs, change, nativeRcaAgent,
                claims, new EvidencePackageValidator(65_536, 32, 4_096),
                "oa_duplicate_orders_current{job=\"order-arena\"}", TOOL_REGISTRY_DIGEST,
                clock, com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                new RunBudgetGate(new InMemoryRunBudgetLedger()), generousLimits(),
                stores.toolLedger);
        orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls,
                new ReportCompletedNotifier(stores.publications, stores.outboxes,
                        List.of("test"), "m6-candidate-v1", 280),
                stores.cas, com.objwww.pr.control.alert.domain.service.SlaPolicy.defaults(),
                clock, "rca", com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                com.objwww.pr.control.release.application.CanaryRouter.holmesOnly(),
                stores.winners);
        incidentId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=checkout",
                IncidentStatus.FIRING, 0, NOW, NOW, null, null, null, 0, 0, 0, null,
                NOW, NOW, NOW, NOW));
    }

    // ------------------------------------------------------------------ 全链

    @Test
    @DisplayName("EX-A0 F14 冻结时间窗：Run 冻结列在场必用（铸时锚），禁止执行期现取窗口")
    void frozenWindowIsUsedInsteadOfExecutionClock() {
        Instant mintedAt = NOW.minusSeconds(120);
        UUID runId = castNativeRunWithInputs(mintedAt);
        NativeInvestigationExecutor driving = executorWithEvidenceProducingTools();

        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        // 三调查任务产出的证据 scope time_range = 冻结窗口（≠执行钟 NOW 面）
        String frozenRange = (mintedAt.minusSeconds(600).getEpochSecond()) + "/"
                + mintedAt.getEpochSecond();
        assertThat(evidence.rows).hasSize(3);
        assertThat(evidence.rows).allSatisfy(e ->
                assertThat(e.scope()).containsEntry("time_range", frozenRange));
        // run 冻结输入身份面在场（RoutingView 读回）
        var view = stores.runs.findRoutingById(runId).orElseThrow();
        assertThat(view.investigationInputDigest()).hasSize(64);
        assertThat(view.windowStart()).isEqualTo(mintedAt.minusSeconds(600));
        assertThat(view.windowEnd()).isEqualTo(mintedAt);
    }

    /** 证据产出型执行器：三 Agent 挂固定成功响应（scope 携带 ctx 的 timeRange/输入身份） */
    private NativeInvestigationExecutor executorWithEvidenceProducingTools() {
        return executorWithEvidenceProducingTools(
                new RunBudgetGate(new InMemoryRunBudgetLedger()));
    }

    /** 同上，预算门外部持有（EX-A3 b2：预算占用保留断言需要直读账面） */
    private NativeInvestigationExecutor executorWithEvidenceProducingTools(
            RunBudgetGate gate) {
        byte[] successWithSeries = "{\"status\":\"success\",\"data\":{\"result\":[{\"x\":1}]}}"
                .getBytes(StandardCharsets.UTF_8);
        com.objwww.pr.control.alert.application.tool.ToolInvoker fixedTools =
                invocation -> new com.objwww.pr.control.alert.application.tool.ToolGateway.ToolInvocationResult(
                        com.objwww.pr.control.alert.application.tool.ToolGateway.ToolInvocationResult.Kind.EXECUTED,
                        "fixed", successWithSeries);
        // agent 挂同一预算门（TOOL_CALL 消费点在 agent 侧）——重驱非免费的账面才真实
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, stores.toolLedger, MAPPER, gate,
                com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.permissive());
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, stores.toolLedger, MAPPER, gate,
                com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.permissive());
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, stores.toolLedger, MAPPER, gate,
                com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.permissive());
        return new NativeInvestigationExecutor(bundles,
                new DeterministicSupervisor(
                        new PlanCompiler(agentRegistry(), stores.tasks, new EdgeStore(),
                                withoutTransaction()),
                        new com.objwww.pr.control.alert.application.DagExecutionService(
                                new EdgeStore(), stores.tasks),
                        stores.runs, stores.tasks, withoutTransaction(), clock),
                stores.tasks, stores.runs, evidence, snapshots, metrics, logs, change,
                new NativeRcaAgent(evidence, snapshots, claims,
                        new ClaimReducer(Set.of(), POLICY_VERSION)),
                claims, new EvidencePackageValidator(65_536, 32, 4_096),
                "oa_duplicate_orders_current{job=\"order-arena\"}", TOOL_REGISTRY_DIGEST,
                clock, com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                gate, generousLimits(), stores.toolLedger);
    }

    /** EX-A1：本件焦点非预算面，宽限额只保证 openRun/TOOL_CALL 硬闸不误伤全链用例 */
    private static Map<BudgetKind, Long> generousLimits() {
        return Map.of(BudgetKind.STEP, 256L, BudgetKind.TOOL_CALL, 256L,
                BudgetKind.EVIDENCE, 256L, BudgetKind.SUBTASK, 64L);
    }

    @Test
    @DisplayName("全链：提案落图→驱动（回放 MISS 降级 DEAD）→快照→REPORTING→报告 engine=NATIVE")
    void fullChainProducesNativeReport() {
        Digest bundleDigest = bundles.publish(nativeBundle(proposal()));
        UUID runId = castNativeRunWithDigest(bundleDigest);
        seedAnnotatedClaims(runId);

        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        RcaRun run = stores.runs.findById(runId).orElseThrow();
        Incident incident = stores.incidents.findById(incidentId).orElseThrow();
        RcaAttempt attempt = newAttempt(driver);

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver, run, incident,
                attempt, () -> { });

        assertThat(result.outcome()).isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        RcaTaskExecutor.AttemptArtifact artifact = result.artifact().orElseThrow();
        assertThat(artifact.validationStatus())
                .isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(artifact.schemaVersion()).isEqualTo(EvidencePackageV2.SCHEMA_VERSION);
        assertThat(artifact.rawText()).contains("NATIVE");

        // 链推进面：DAG 任务全 DEAD（回放 MISS 缺源降级）、run 入 REPORTING、快照已冻结
        assertThat(stores.tasks.findByRunId(runId))
                .filteredOn(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DEAD));
        // driver 本体归 worker/finishTask 收尾（直调执行器不动它；夹具已模拟领取=LEASED）
        assertThat(stores.tasks.findByRunId(runId))
                .filteredOn(t -> t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .singleElement()
                .satisfies(t -> assertThat(t.state()).isEqualTo(RcaTaskState.LEASED));
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
        assertThat(snapshots.frozen).hasSize(1);
        assertThat(snapshots.frozen.get(0).configDigest())
                .isEqualTo(bundleDigest.hex());
        assertThat(claims.appended).hasSize(1);

        // 收尾复用 finishTask：报告 + 发布 + outbox 同链落库（FUT-49 共用出口）
        var outcome = orchestrator.finishTask(driver, "worker-a", -1, -1, result, attempt);
        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.reports.all()).hasSize(1);
        assertThat(stores.reports.all().get(0).rawText()).contains("NATIVE");
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);
    }

    @Test
    @DisplayName("提案缺失 fail-closed：路由 digest 无 bundle 行 → PROPOSAL_MISSING（active 在场也不代用——EN-03 身份锚）")
    void proposalMissingFailsClosed() {
        bundles.publish(nativeBundle(proposal()));   // active 在场且提案合法
        UUID runId = castNativeRunWithDigest(Digest.sha256Of("ghost-bundle"));

        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.FAILED_TERMINAL);
        assertThat(result.errorClass()).isEqualTo("PROPOSAL_MISSING");
        assertThat(result.artifact()).isEmpty();
        // 零驱动：run 仍在 QUEUED、零 DAG 任务、零快照、零 Claim
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.QUEUED);
        assertThat(stores.tasks.findByRunId(runId)).hasSize(1);
        assertThat(snapshots.frozen).isEmpty();
        assertThat(claims.appended).isEmpty();
    }

    @Test
    @DisplayName("提案非法 fail-closed：PROPOSAL_REJECTED → Supervisor 置 run FAILED，执行器终态")
    void proposalRejectedFailsClosed() {
        UUID runId = castNativeRunWithBundle(nativeBundle(Map.of("schema_version", "bogus",
                "tasks", List.of(), "edges", List.of())));

        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.FAILED_TERMINAL);
        assertThat(result.errorClass()).isEqualTo("PROPOSAL_REJECTED");
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.FAILED);
        assertThat(snapshots.frozen).isEmpty();
        assertThat(claims.appended).isEmpty();
    }

    // ------------------------------------------------ EX-A3 F08/F09 四阶段恢复

    /** 完跑一轮（三任务 DONE、3 证据、3 SUCCESS 行）后按 key 取 DAG 任务行 */
    private RcaTask dagTask(UUID runId, String key) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow();
    }

    /** 状态翻转（模拟 driver 崩溃残留的任务现场；其余列原样） */
    private void flipTask(RcaTask task, RcaTaskState to) {
        stores.tasks.update(new RcaTask(task.id(), task.runId(), task.taskKey(), to,
                task.priority(), task.availableAt(), task.readySince(), task.deadlineAt(),
                task.leaseOwner(), task.leaseUntil(), task.leaseEpoch(),
                task.attemptCount(), task.maxAttempts(), task.createdAt(), NOW));
    }

    @Test
    @DisplayName("EX-A3 b4 阶段④：任务与结果已提交 → 重驱零动作（重放读取既有结论）")
    void stage4_committedTasksAreSkipped() {
        UUID runId = castNativeRun();
        NativeInvestigationExecutor driving = executorWithEvidenceProducingTools();
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        int evidenceBefore = evidence.rows.size();
        int ledgerBefore = stores.toolLedger.rows.size();
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(evidence.rows).hasSize(evidenceBefore);
        assertThat(stores.toolLedger.rows).hasSize(ledgerBefore);
        assertThat(stores.tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE)))
                .allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DONE));
    }

    @Test
    @DisplayName("EX-A3 b3 阶段③：结果已落库任务未完成 → result_ref 幂等收尾，零触网零新行")
    void stage3_resultRefIdempotentCompletionWithoutNetwork() {
        UUID runId = castNativeRun();
        NativeInvestigationExecutor driving = executorWithEvidenceProducingTools();
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        // 崩溃窗：SUCCESS 收据在账但任务停在 RUNNING（insert→succeed→DONE 之间被杀）
        RcaTask metrics = dagTask(runId, "investigate-metrics");
        flipTask(metrics, RcaTaskState.RUNNING);
        int evidenceBefore = evidence.rows.size();
        int ledgerBefore = stores.toolLedger.rows.size();

        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(dagTask(runId, "investigate-metrics").state())
                .as("阶段③：从 result_ref 幂等收尾 DONE").isEqualTo(RcaTaskState.DONE);
        assertThat(evidence.rows).as("零新证据（零触网）").hasSize(evidenceBefore);
        assertThat(stores.toolLedger.rows).as("零新账本行（不重复调用）")
                .hasSize(ledgerBefore);
        var metricsRows = stores.toolLedger.rows.values().stream()
                .filter(r -> r.identity.taskId().equals(metrics.id())).toList();
        assertThat(metricsRows).hasSize(1);
        assertThat(metricsRows.get(0).resultRef)
                .as("checkpoint result_ref 在账（agent 落值面）").isNotNull();
    }

    @Test
    @DisplayName("EX-A3 b2 阶段②：请求已发出结果未知 → 旧行 UNKNOWN 预算占用保留 + 新物理请求成对（不免费重发）")
    void stage2_pendingOrphanMarkedUnknownAndRedrivenAsNewBudgetedCall() {
        UUID runId = castNativeRun();
        com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger budgetLedger =
                new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger();
        NativeInvestigationExecutor driving =
                executorWithEvidenceProducingTools(new RunBudgetGate(budgetLedger));
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        // 崩溃窗：invoke 在途——悬挂 PENDING 行 + PROVISIONAL 预留（发送资格已取得）；
        // 原 SUCCESS 收据随崩溃窗清除（单调用任务：收据在账即走阶段③，轮不到阶段②）
        RcaTask metrics = dagTask(runId, "investigate-metrics");
        UUID orphanAttempt = UUID.randomUUID();
        ReservationKey orphanKey = new ReservationKey(runId, metrics.id(), orphanAttempt,
                99, BudgetKind.TOOL_CALL);
        budgetLedger.ensureLimit(runId, BudgetKind.TOOL_CALL, 256);
        budgetLedger.reserve(orphanKey, 1);
        budgetLedger.provisional(orphanKey);
        stores.toolLedger.rows.values()
                .removeIf(r -> r.identity.taskId().equals(metrics.id()));
        stores.toolLedger.open(new RcaToolInvocationLedger.InvocationIdentity(
                UUID.randomUUID(), runId, metrics.id(), orphanAttempt, 99,
                MetricsAgent.TOOL_NAME, "1", Digest.sha256Of("orphan").hex()));
        flipTask(metrics, RcaTaskState.RUNNING);
        long consumedBeforeRedrive = budgetLedger.consumedOf(runId, BudgetKind.TOOL_CALL);
        int evidenceBefore = evidence.rows.size();

        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        var orphanRow = stores.toolLedger.rows.values().stream()
                .filter(r -> r.identity.callSeq() == 99).findFirst().orElseThrow();
        assertThat(orphanRow.state).as("发送后结果未知 → UNKNOWN 诚实归档")
                .isEqualTo(ToolInvocationState.UNKNOWN);
        assertThat(orphanRow.reason).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
        assertThat(budgetLedger.stateOf(orphanKey)).as("预算占用保留（不退款不免费）")
                .isEqualTo("PROVISIONAL");
        var metricsRows = stores.toolLedger.rows.values().stream()
                .filter(r -> r.identity.taskId().equals(metrics.id())).toList();
        assertThat(metricsRows).hasSize(2);
        var reDriven = metricsRows.stream()
                .filter(r -> r.state == ToolInvocationState.SUCCESS).findFirst().orElseThrow();
        assertThat(reDriven.identity.callSeq())
                .as("重驱=新 call_seq 新预算（call_seq 跨 attempt 单调）")
                .isGreaterThan(99L);
        assertThat(evidence.rows).as("只读工具同冻结窗重查=留新观察记录")
                .hasSize(evidenceBefore + 1);
        assertThat(dagTask(runId, "investigate-metrics").state()).isEqualTo(RcaTaskState.DONE);
        assertThat(budgetLedger.consumedOf(runId, BudgetKind.TOOL_CALL))
                .as("新物理请求消耗新预算单位（重驱非免费：种子前 3 + 孤儿 1 + 重驱 1）")
                .isEqualTo(consumedBeforeRedrive + 1);
        assertThat(stores.tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .filter(t -> t.state() == RcaTaskState.RUNNING)).as("无永久 RUNNING").isEmpty();
    }

    @Test
    @DisplayName("EX-A3 b1 阶段①：任务开始未取得发送资格（无账本行）→ 常规重驱；FAILED 回执孤儿 → DEAD 不重复调用")
    void stage1_noReceiptOrphansAndFailedReceipts() {
        UUID runId = castNativeRun();
        NativeInvestigationExecutor driving = executorWithEvidenceProducingTools();
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });
        int evidenceAfterFirstPass = evidence.rows.size();

        // b1a：任务 RUNNING 但零账本行（迁移后、open 前被杀）→ 常规重驱
        RcaTask change = dagTask(runId, "investigate-change");
        stores.toolLedger.rows.values()
                .removeIf(r -> r.identity.taskId().equals(change.id()));
        flipTask(change, RcaTaskState.RUNNING);
        int evidenceBeforeRedrive = evidence.rows.size();
        long maxPriorCallSeq = stores.toolLedger.rows.values().stream()
                .mapToLong(r -> r.identity.callSeq()).max().orElse(0);

        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(dagTask(runId, "investigate-change").state())
                .isEqualTo(RcaTaskState.DONE);
        assertThat(evidence.rows).hasSize(evidenceBeforeRedrive + 1);
        var changeRows = stores.toolLedger.rows.values().stream()
                .filter(r -> r.identity.taskId().equals(change.id())).toList();
        assertThat(changeRows).hasSize(1);
        assertThat(changeRows.get(0).identity.callSeq())
                .as("call_seq 跨 attempt 单调（从既有 checkpoint 最大值续起）")
                .isEqualTo(maxPriorCallSeq + 1);

        // b1b：FAILED 回执孤儿（收尾前被杀）→ DEAD 降级，不重复调用
        RcaTask logs = dagTask(runId, "investigate-logs");
        flipTask(logs, RcaTaskState.RUNNING);
        stores.toolLedger.rows.values().stream()
                .filter(r -> r.identity.taskId().equals(logs.id()))
                .forEach(r -> {
                    r.state = ToolInvocationState.FAILED;
                    r.reason = ToolReasonCode.TRANSPORT_UNKNOWN;
                });
        int evidenceBeforeFailedCase = evidence.rows.size();
        int ledgerBeforeFailedCase = stores.toolLedger.rows.size();

        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        assertThat(dagTask(runId, "investigate-logs").state())
                .as("已知失败回执 → DEAD（缺源降级），不重复调用")
                .isEqualTo(RcaTaskState.DEAD);
        assertThat(evidence.rows).hasSize(evidenceBeforeFailedCase);
        assertThat(stores.toolLedger.rows).hasSize(ledgerBeforeFailedCase);
        assertThat(evidenceAfterFirstPass).isEqualTo(3);
        assertThat(stores.tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .filter(t -> t.state() == RcaTaskState.RUNNING)).as("无永久 RUNNING").isEmpty();
    }

    @Test
    @DisplayName("EN-03 S11 调用固定：指针铸后移到 v2 → 旧 Run 仍按 v1 提案驱动（drive 期不读 active）")
    void pointerMoveDoesNotReshapeRunningRun() {
        Digest v1 = bundles.publish(nativeBundle(proposal()));
        UUID runId = castNativeRunWithDigest(v1);
        Digest v2 = bundles.publish(nativeBundle(proposalV2SingleTask()));
        assertThat(v2).isNotEqualTo(v1);

        NativeInvestigationExecutor driving = executorWithEvidenceProducingTools();
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        driving.execute(driver, stores.runs.findById(runId).orElseThrow(),
                stores.incidents.findById(incidentId).orElseThrow(),
                newAttempt(driver), () -> { });

        // 提案源 = 铸时 v1（三任务 DAG 落图）；读 active 会落 v2 单任务形状（S11 违约）
        assertThat(stores.tasks.findByRunId(runId))
                .filteredOn(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .hasSize(3);
        // 快照身份面 = 铸时 v1 digest（不随指针漂移）
        assertThat(snapshots.frozen).hasSize(1);
        assertThat(snapshots.frozen.get(0).configDigest()).isEqualTo(v1.hex());
    }

    // ------------------------------------------------------------------ 夹具

    /** 铸 NATIVE run + driver task（生产同型：准入读指针 → run 固定该 bundle digest） */
    private UUID castNativeRun() {
        return castNativeRunWithBundle(nativeBundle(proposal()));
    }

    /** 先发布指定内容（指针落下）再铸造——路由 digest = 该 bundle digest（准入固定面） */
    private UUID castNativeRunWithBundle(Map<String, Object> content) {
        return castNativeRunWithDigest(bundles.publish(content));
    }

    /** 以指定 digest 铸造（EN-03 身份锚用例：可铸无行 ghost digest 验 fail-closed） */
    private UUID castNativeRunWithDigest(Digest routingDigest) {
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, GENERATION, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null, null, null);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                routingDigest, "g:checkout", 42, "BUCKETED_NATIVE"));
        insertDriverTask(runId);
        markDriverLeased(runId);
        return runId;
    }

    /** EX-A0 铸点：路由四列 + 调查输入三列（输入 digest/冻结窗口）随行落库 */
    private UUID castNativeRunWithInputs(Instant mintedAt) {
        Digest routingDigest = bundles.publish(nativeBundle(proposal()));
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, GENERATION, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null, null, null);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                        routingDigest, "g:checkout", 42, "BUCKETED_NATIVE"),
                com.objwww.pr.control.alert.domain.identity.InvestigationInputs.freezeAt(
                        stores.incidents.findById(incidentId).orElseThrow(), mintedAt));
        insertDriverTask(runId);
        markDriverLeased(runId);
        return runId;
    }

    private void insertDriverTask(UUID runId) {
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId,
                RcaTask.taskKeyFor(RcaEngine.NATIVE), RcaTaskState.READY, 5, NOW, NOW,
                Instant.MAX, null, null, 0, 0, 3, NOW, NOW));
    }

    /** 模拟 worker 领取（finishTask 租约栅栏的当前租约锚：owner+epoch） */
    private void markDriverLeased(UUID runId) {
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        stores.tasks.update(new RcaTask(driver.id(), driver.runId(), driver.taskKey(),
                RcaTaskState.LEASED, driver.priority(), driver.availableAt(),
                driver.readySince(), driver.deadlineAt(), "worker-a",
                NOW.plus(Duration.ofMinutes(5)), 0, 1, driver.maxAttempts(),
                driver.createdAt(), NOW));
    }

    private RcaAttempt newAttempt(RcaTask driver) {
        return new RcaAttempt(UUID.randomUUID(), driver.id(), 1, driver.leaseEpoch() + 1,
                "worker-a", RcaAttemptStatus.STARTED, null, null, null, NOW, null, null);
    }

    /** 预置双源断言注记证据（无 input_snapshot_digest——不触发异快照排除） */
    private void seedAnnotatedClaims(UUID runId) {
        evidence.insert(annotated(runId, "prometheus", "cpu over 95%"));
        evidence.insert(annotated(runId, "logs", "cpu over 95%"));
    }

    private EvidenceEnvelope annotated(UUID runId, String source, String reason) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("claim_key", "cpu_saturation");
        scope.put("claim_status", "TRUE");
        scope.put("reason", reason);
        scope.put("scope", "svc-a");
        scope.put("time_range", "09:50/10:00");
        return EvidenceEnvelope.create(UUID.randomUUID(), runId, UUID.randomUUID(),
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, GENERATION, source,
                scope, null, null,
                Map.of("status", "success", "data", Map.of("result", List.of("x"))));
    }

    /** 固定提案：三调查任务全并行（零边 = 全根任务） */
    private static Map<String, Object> proposal() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("key", "investigate-metrics", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "investigate-logs", "type", "logs@1", "inputs", List.of()),
                Map.of("key", "investigate-change", "type", "change@1", "inputs", List.of()));
        return Map.of("schema_version", com.objwww.pr.control.alert.domain.dag.PlanProposal.SCHEMA_VERSION,
                "tasks", tasks, "edges", List.of());
    }

    /** v2 提案：单任务（EN-03 S11 用——与 v1 三任务形状可区分） */
    private static Map<String, Object> proposalV2SingleTask() {
        return Map.of("schema_version",
                com.objwww.pr.control.alert.domain.dag.PlanProposal.SCHEMA_VERSION,
                "tasks", List.of(Map.of("key", "investigate-metrics", "type", "metrics@1",
                        "inputs", List.of())),
                "edges", List.of());
    }

    private static Map<String, Object> nativeBundle(Map<String, Object> proposal) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("native", Map.of("proposal", proposal));
        return content;
    }

    private static AgentProfile profile(String name, String tool) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv", Set.of(tool),
                Map.of(BudgetKind.TOOL_CALL, 4L), Map.of("type", "object"));
    }

    private static AgentRegistry agentRegistry() {
        return new AgentRegistry(List.of(
                new AgentProfile("metrics", "1", "prompt-m", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("logs", "1", "prompt-l", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("change", "1", "prompt-c", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"))));
    }

    private static ToolRegistry replayRegistry() {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(MetricsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(LogsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(ChangeAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8)))));
    }

    private static TransactionOperations withoutTransaction() {
        return new TransactionOperations() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    // ------------------------------------------------------------------ 迷你认账面

    static final class TestBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        Digest active;

        Digest publish(Map<String, Object> content) {
            ConfigBundle bundle = ConfigBundle.of(content, "op", NOW);
            rows.add(bundle);
            active = bundle.bundleDigest();
            return bundle.bundleDigest();
        }

        @Override
        public long nextRevision() {
            return rows.size() + 1;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return rows.add(bundle);
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return Optional.empty();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            active = toDigest;
            return true;
        }
    }

    static final class TestClaims implements ClaimStore {
        final List<ClaimVerdict> appended = new ArrayList<>();

        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appended.add(verdict);
            return new ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return appended.stream().map(v -> new ClaimRow(UUID.randomUUID(), runId,
                    v.fingerprint(), v.contentHash(), v.claimKey(), v.status(),
                    v.evidenceBasis(), ClaimLifecycle.ACTIVE, v.reason(), v.scope(),
                    v.timeRange(), v.observedGeneration(), v.sources(), v.evidenceRefs(),
                    v.policyVersion(), v.snapshotDigest())).toList();
        }
    }

    static final class TestEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }

    static final class TestSnapshots implements EvidenceSnapshotRepository {
        final List<FrozenSnapshot> frozen = new ArrayList<>();
        private final Map<UUID, List<SnapshotMemberRow>> members = new LinkedHashMap<>();

        @Override
        public boolean freeze(FrozenSnapshot snapshot, List<SnapshotMemberRow> memberRows) {
            frozen.add(snapshot);
            members.put(snapshot.snapshotId(), List.copyOf(memberRows));
            return true;
        }

        @Override
        public Optional<FrozenSnapshot> find(UUID runId, String snapshotDigest) {
            return frozen.stream().filter(s -> s.runId().equals(runId)
                    && s.snapshotDigest().equals(snapshotDigest)).findFirst();
        }

        /** EX-A4a（F05）黑板面：成员行落账 + evidence_id 序稳定 */
        @Override
        public List<SnapshotMemberRow> membersOf(UUID snapshotId) {
            return members.getOrDefault(snapshotId, List.of()).stream()
                    .sorted(java.util.Comparator.comparing(SnapshotMemberRow::evidenceId))
                    .toList();
        }
    }

    static final class TestReplayStore implements ToolReplayStore {
        @Override
        public void put(ReplayRecord record) {
        }

        @Override
        public Optional<ReplayRecord> find(String actionDigest) {
            return Optional.empty();
        }
    }

    /** 最小边仓储内存件（Am4ShadowFullChainG2Test 同形） */
    static final class EdgeStore implements TaskEdgeRepository {
        private final List<UUID> runIds = new ArrayList<>();
        private final List<TaskEdge> edgeRows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            runIds.add(runId);
            edgeRows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(),
                    dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            List<TaskEdge> matched = new ArrayList<>();
            for (int k = 0; k < runIds.size(); k++) {
                if (runIds.get(k).equals(runId)) {
                    matched.add(edgeRows.get(k));
                }
            }
            return matched;
        }
    }
}
