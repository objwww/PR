package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.holmes.HolmesEvidenceAdapter;
import com.objwww.pr.control.alert.application.reconcile.IncidentSourceReconciler;
import com.objwww.pr.control.alert.application.reconcile.ReconcileBudgetGate;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.replay.SnapshotShadowRouter;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.RunBudget;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
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
 * AM4 G2 全链组件套件（M4-38，验收证据面）：Native Shadow 全链路但不切主——
 * 六轴一次组装实证：① DAG（startRun→崩溃重驱动→advance→REPORTING）；② 预算
 * （两侧 RunBudget 独立扣减 + ReconcileBudgetGate 耗尽终态停自循环产事件）；
 * ③ 证据（回放工具产证据全部盖章同 snapshot digest + 账本 SUCCESS）；④ 对账
 * （IncidentSourceReconciler 双闸收敛）；⑤ Holmes 隔离（Baseline 独立归并，
 * Candidate 失败被路由器捕获隔离）；⑥ <b>不切主/Candidate 增量 0</b>（影子链全程
 * 零报告、零发布落库——reports/publications 存储空断言）。真栈 E2E-M4-08/09
 * （e2e-m4-09-shadow.sh）归 195 真栈接线批次，本套件为组件级 G2 证据。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class Am4ShadowFullChainG2Test {

    private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
    private static final long GENERATION = 7L;
    private static final String SNAPSHOT = "ab".repeat(32);
    /** EX-A0：工具/Agent 上下文的输入身份（类型化；SNAPSHOT 字符串保留给 router.compare(String)） */
    private static final com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
            INPUT = new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                    SNAPSHOT);
    private static final long DEADLINE = 9_000_000_000_000L;
    private static final String POLICY_VERSION = "g2-policy";
    private static final String HOLMES_PACKAGE = ("{\"claims\":["
            + "{\"claim_type\":\"cpu_saturation\",\"status\":\"TRUE\",\"component\":\"svc-a\","
            + "\"evidence_refs\":[\"" + UUID.randomUUID() + "\"]},"
            + "{\"claim_type\":\"config_change\",\"status\":\"FALSE\",\"component\":\"svc-b\","
            + "\"evidence_refs\":[\"" + UUID.randomUUID() + "\"]}]}");
    private static final byte[] TOOL_RESPONSE =
            "{\"status\":\"success\",\"data\":{\"result\":[\"g2\"]}}"
                    .getBytes(StandardCharsets.UTF_8);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final EdgeStore edges = new EdgeStore();
    private final G2ClaimStore baselineClaims = new G2ClaimStore();
    private final G2ClaimStore candidateClaims = new G2ClaimStore();
    private final G2Evidence candidateEvidence = new G2Evidence();
    private final G2Ledger candidateLedger = new G2Ledger();
    private final AlertInMemoryStores.Snapshots candidateSnapshots =
            new AlertInMemoryStores.Snapshots();
    private final ClaimReducer reducer = new ClaimReducer(
            Set.of("holmes", "prometheus"), POLICY_VERSION);

    // ------------------------------------------------------- 主链：六轴一次走通

    @Test
    void fullShadowChainRunsToReportingWithoutPublishing() {
        // ① DAG 轴：固定提案落图 → 崩溃重驱动（全新 Supervisor 同存储）→ 全 DONE → REPORTING
        UUID runId = castRun();
        DeterministicSupervisor supervisor = supervisor();
        assertThat(supervisor.startRun(runId, proposal(), knownArtifacts()).outcome())
                .isEqualTo(DeterministicSupervisor.StartOutcome.STARTED);

        // 进程崩溃重启：全新 Supervisor 实例、同一存储——恢复入口只有 advance
        DeterministicSupervisor restarted = supervisor();
        restarted.advance(runId);
        assertThat(taskState(runId, "investigate-a")).isEqualTo(RcaTaskState.READY);
        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.BLOCKED);

        setTask(runId, "investigate-a", RcaTaskState.DONE);
        setTask(runId, "investigate-b", RcaTaskState.DONE);
        setTask(runId, "reduce", RcaTaskState.DONE);
        restarted.advance(runId);
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);

        // ②⑤ 影子对照：Baseline=Holmes 链，Candidate=回放工具链 + Native RCA
        RunBudget baselineBudget = new RunBudget(100, 4, 100, 100, DEADLINE);
        RunBudget candidateBudget = new RunBudget(100, 3, 100, 100, DEADLINE);
        SnapshotShadowRouter.ShadowResult shadow = new SnapshotShadowRouter().compare(
                SNAPSHOT,
                this::baselineRun,
                (digest, budget) -> candidateRun(runId, digest, budget),
                baselineBudget, candidateBudget);

        assertThat(shadow.baseline().succeeded()).isTrue();
        assertThat(shadow.baseline().claimCount()).isEqualTo(2);
        assertThat(shadow.candidate().succeeded()).isTrue();
        assertThat(shadow.candidate().claimCount()).isEqualTo(1);
        assertThat(shadow.baseline().snapshotDigest()).isEqualTo(SNAPSHOT);
        assertThat(shadow.candidate().snapshotDigest()).isEqualTo(SNAPSHOT);

        // ③ 证据轴：回放产出的证据全部盖章同 snapshot digest，账本全 SUCCESS
        assertThat(candidateEvidence.rows).hasSize(4); // 3 回放原始 + 1 断言注记
        assertThat(candidateEvidence.rows).allSatisfy(e ->
                assertThat(e.scope().get("investigation_input_digest")).isEqualTo(SNAPSHOT));
        assertThat(candidateLedger.rows).hasSize(3);
        assertThat(candidateLedger.rows).allSatisfy(r ->
                assertThat(r.state()).isEqualTo(ToolInvocationState.SUCCESS));
        assertThat(baselineClaims.appended).allSatisfy(v ->
                assertThat(v.snapshotDigest()).isEqualTo(SNAPSHOT));
        assertThat(candidateClaims.appended).allSatisfy(v ->
                assertThat(v.snapshotDigest()).isEqualTo(SNAPSHOT));

        // ② 预算轴：两侧独立扣减（Baseline 1/4，Candidate 3/3）
        assertThat(baselineBudget.remaining(RunBudget.Kind.TOOL_CALL)).isEqualTo(3);
        assertThat(candidateBudget.remaining(RunBudget.Kind.TOOL_CALL)).isZero();

        // ⑥ 不切主/Candidate 增量 0：影子链全程零报告、零发布落库
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
    }

    // ------------------------------------------------- Candidate 失败隔离（Holmes 隔离轴）

    @Test
    void candidateFailureMidChainIsIsolatedAndBaselineStands() {
        UUID runId = castRun();
        RunBudget baselineBudget = new RunBudget(100, 4, 100, 100, DEADLINE);
        RunBudget zeroCandidateBudget = new RunBudget(100, 0, 100, 100, DEADLINE);

        SnapshotShadowRouter.ShadowResult shadow = new SnapshotShadowRouter().compare(
                SNAPSHOT,
                this::baselineRun,
                (digest, budget) -> candidateRun(runId, digest, budget),
                baselineBudget, zeroCandidateBudget);

        // Candidate 预算耗尽被路由器捕获隔离：结局显式失败（原因可见），Baseline 站得住
        assertThat(shadow.candidate().succeeded()).isFalse();
        assertThat(shadow.candidate().failReason()).contains("Budget");
        assertThat(shadow.baseline().succeeded()).isTrue();
        assertThat(shadow.baseline().claimCount()).isEqualTo(2);
        assertThat(candidateClaims.appended).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
    }

    // ------------------------------------------------- 对账收敛 + 预算门停自循环

    @Test
    void reconcilerConvergesAndBudgetGateStopsSelfLoop() {
        // ④ 对账轴：连续 3 次缺席且宽限窗届满才收敛（窗口起点锚定首次缺席）
        long base = 1_000_000_000L;
        G2ReconcileSink sink = new G2ReconcileSink(Set.of());
        IncidentSourceReconciler reconciler = new IncidentSourceReconciler(
                3, 10 * 60_000L, sink, sink);
        assertThat(reconciler.reconcile("inc-1", "fp", 0, 0, base).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
        assertThat(reconciler.reconcile("inc-1", "fp", 1, base,
                base + 5 * 60_000L).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
        assertThat(reconciler.reconcile("inc-1", "fp", 2, base,
                base + 10 * 60_000L).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.RESOLVED);
        assertThat(sink.observations).hasSize(3);

        // ② 预算门：耗尽 → TERMINAL + 确定性事件（六类共用同一门，类别标签进事件）
        ReconcileBudgetGate.GateConfig config = new ReconcileBudgetGate.GateConfig(
                5, 3_600_000L, 86_400_000L, 30_000L, 480_000L);
        for (String kind : Set.of("incident-source", "budget-provisional")) {
            ReconcileBudgetGate.GateOutcome outcome = new ReconcileBudgetGate().evaluate(
                    kind, config, new ReconcileBudgetGate.GateState(5, 0L, 0L),
                    ReconcileBudgetGate.Failure.NONE, false, true, 1_000L);
            assertThat(outcome.routing()).isEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
            assertThat(outcome.event().reconcilerKind()).isEqualTo(kind);
            assertThat(outcome.event().reason()).contains("ATTEMPTS");
        }
    }

    // ------------------------------------------------------------------ 影子两侧

    /** Baseline 侧：Holmes 报告包 → Claim 归并落库（真实 Adapter 代码） */
    private int baselineRun(String snapshotDigest, RunBudget budget) {
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        HolmesEvidenceAdapter.AdaptResult adapted = new HolmesEvidenceAdapter().adapt(
                UUID.randomUUID(), UUID.randomUUID(), GENERATION, snapshotDigest,
                EvidenceEnvelope.SCHEMA_VERSION, HOLMES_PACKAGE);
        if (adapted.outcome() != HolmesEvidenceAdapter.AdaptOutcome.ADAPTED) {
            throw new IllegalStateException(String.valueOf(adapted.failReason()));
        }
        int count = 0;
        for (ClaimVerdict verdict : reducer.reduce(adapted.claims())) {
            baselineClaims.append(UUID.randomUUID(), verdict);
            count++;
        }
        return count;
    }

    /** Candidate 侧：回放工具链三 Agent 产原始证据 + 注记断言证据 → Native RCA 落库 */
    private int candidateRun(UUID runId, String snapshotDigest, RunBudget budget) {
        AgentReplayRunner runner = new AgentReplayRunner(
                new ReplayToolGateway(shadowRegistry(), new G2ReplayStore()));
        MetricsAgent.CallContext ctx = new MetricsAgent.CallContext(runId, UUID.randomUUID(),
                UUID.randomUUID(), 1L, GENERATION,
                new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                        snapshotDigest),
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z");
        MetricsAgent metrics = new MetricsAgent(metricsProfile(), shadowRegistry(), runner,
                candidateEvidence, candidateLedger, ReplayJson.MAPPER);
        LogsAgent logs = new LogsAgent(logsProfile(), shadowRegistry(), runner,
                candidateEvidence, candidateLedger, ReplayJson.MAPPER);
        ChangeAgent change = new ChangeAgent(changeProfile(), shadowRegistry(), runner,
                candidateEvidence, candidateLedger, ReplayJson.MAPPER);
        // 基线录制：三工具各录一次（全匹配回放）
        runner.record(metricsInvocation(runId), TOOL_RESPONSE);
        runner.record(logsInvocation(runId), TOOL_RESPONSE);
        runner.record(changeInvocation(runId), TOOL_RESPONSE);
        // 每工具调用扣一次 TOOL_CALL（三次回放全匹配，扣满 Candidate 侧 3/3）
        metrics.investigate(ctx, new MetricsAgent.MetricsQuery(
                "cpu_usage_percent", "1757059200", "1757059260", "30s"));
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        logs.investigate(ctx, new LogsAgent.LogsQuery("1757059200", "1757059260"));
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        change.investigate(ctx, new ChangeAgent.ChangeQuery("1757059200", "1757059260"));
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        assertThat(runner.stats().hits()).isEqualTo(3);
        assertThat(runner.stats().misses()).isZero();

        // 断言注记证据（注记生产者归后续批次；M4-30 消费面在此验证）
        candidateEvidence.insert(annotatedEvidence(runId, snapshotDigest));

        // EX-A4a（F05）：黑板=冻结快照成员——先冻结当前证据集再推导
        List<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow>
                members = candidateEvidence.rows.stream()
                .map(e -> new com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow(
                        e.evidenceId(), e.evidenceType(), e.payloadDigest()))
                .toList();
        candidateSnapshots.freeze(new com.objwww.pr.control.alert.domain.evidence
                .EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(), runId,
                snapshotDigest, GENERATION, "cfg", "tools", null), members);
        NativeRcaAgent candidate = new NativeRcaAgent(candidateEvidence, candidateSnapshots,
                candidateClaims, reducer);
        return candidate.investigate(runId,
                new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                        snapshotDigest),
                new com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest(
                        snapshotDigest),
                GENERATION).verdicts().size();
    }

    private ToolRegistry shadowRegistry() {
        ToolDefinition metrics = MetricsAgent.toolDefinition(4_000L, 65_536L);
        ToolDefinition logs = LogsAgent.toolDefinition(4_000L, 65_536L);
        ToolDefinition change = ChangeAgent.toolDefinition(4_000L, 65_536L);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(metrics, new ReplayToolExecutor(TOOL_RESPONSE)),
                new ToolRegistry.Registration(logs, new ReplayToolExecutor(TOOL_RESPONSE)),
                new ToolRegistry.Registration(change, new ReplayToolExecutor(TOOL_RESPONSE))));
    }

    private EvidenceEnvelope annotatedEvidence(UUID runId, String snapshotDigest) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("investigation_input_digest", snapshotDigest);
        scope.put("claim_key", "cpu_saturation");
        scope.put("claim_status", "TRUE");
        scope.put("reason", "cpu over threshold");
        scope.put("scope", "svc-a");
        scope.put("time_range", "07:50/08:00");
        return EvidenceEnvelope.create(UUID.randomUUID(), runId, UUID.randomUUID(),
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, GENERATION,
                "prometheus", scope, null, null,
                Map.of("status", "success", "data", Map.of("result", List.of("x"))));
    }

    private ToolGateway.ToolInvocation metricsInvocation(UUID runId) {
        return new ToolGateway.ToolInvocation(runId, UUID.randomUUID(), UUID.randomUUID(),
                1L, MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", MetricsAgent.argsOf(
                        new MetricsAgent.MetricsQuery("cpu_usage_percent", "1757059200",
                                "1757059260", "30s")), INPUT);
    }

    private ToolGateway.ToolInvocation logsInvocation(UUID runId) {
        return new ToolGateway.ToolInvocation(runId, UUID.randomUUID(), UUID.randomUUID(),
                2L, LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", LogsAgent.argsOf(
                        new LogsAgent.LogsQuery("1757059200", "1757059260")), INPUT);
    }

    private ToolGateway.ToolInvocation changeInvocation(UUID runId) {
        return new ToolGateway.ToolInvocation(runId, UUID.randomUUID(), UUID.randomUUID(),
                3L, ChangeAgent.TOOL_NAME, ChangeAgent.TOOL_VERSION,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", ChangeAgent.argsOf(
                        new ChangeAgent.ChangeQuery("1757059200", "1757059260")), INPUT);
    }

    private static AgentProfile metricsProfile() {
        return new AgentProfile("metrics", "1", "prompt-metrics", "pv",
                Set.of(MetricsAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static AgentProfile logsProfile() {
        return new AgentProfile("logs", "1", "prompt-logs", "pv",
                Set.of(LogsAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static AgentProfile changeProfile() {
        return new AgentProfile("change", "1", "prompt-change", "pv",
                Set.of(ChangeAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    // ------------------------------------------------------------------ DAG 夹具（同 M4-26 形状）

    private DeterministicSupervisor supervisor() {
        return new DeterministicSupervisor(
                new PlanCompiler(agents(), stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents(), inPlaceTx(), () -> NOW);
    }

    private static AgentRegistry agents() {
        return new AgentRegistry(List.of(
                new AgentProfile("metrics", "1", "prompt-m", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("logs", "1", "prompt-l", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("reduce", "1", "prompt-r", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"))));
    }

    private UUID castRun() {
        UUID runId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null, null, null));
        return runId;
    }

    private void setTask(UUID runId, String key, RcaTaskState state) {
        RcaTask task = stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow();
        boolean updated = stores.tasks.update(new RcaTask(task.id(), task.runId(),
                task.taskKey(), state, task.priority(), task.availableAt(), task.readySince(),
                task.deadlineAt(), null, null, task.leaseEpoch(), task.attemptCount(),
                task.maxAttempts(), task.createdAt(), NOW));
        assertThat(updated).as("task %s 状态直置 %s", key, state).isTrue();
    }

    private RcaTaskState taskState(UUID runId, String key) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow().state();
    }

    private static Map<String, Object> proposal() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("key", "investigate-a", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "investigate-b", "type", "logs@1", "inputs", List.of()),
                Map.of("key", "reduce", "type", "reduce@1", "inputs", List.of("snapshot:r0")));
        List<Map<String, Object>> edges = List.of(
                Map.of("from", "investigate-a", "to", "reduce", "dependency", "REQUIRED"),
                Map.of("from", "investigate-b", "to", "reduce", "dependency", "REQUIRED"));
        return Map.of("schema_version", "am4-plan.v1", "tasks", tasks, "edges", edges);
    }

    private static Set<String> knownArtifacts() {
        return Set.of("snapshot:r0");
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件（同 M4-26 形状） */
    private static final class EdgeStore implements TaskEdgeRepository {

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

    // ------------------------------------------------------------------ 共享内存件

    /** G2 专用 JSON/Mapper 持有（避免测试间共享可变状态） */
    private static final class ReplayJson {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }

    static final class G2ClaimStore implements ClaimStore {
        final List<ClaimVerdict> appended = new ArrayList<>();

        @Override
        public ClaimStore.ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appended.add(verdict);
            return new ClaimStore.ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimStore.ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    }

    static final class G2Evidence implements EvidenceRepository {
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

    static final class G2Ledger implements RcaToolInvocationLedger {
        record Row(UUID operationId, String toolName, ToolInvocationState state,
                ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.toolName(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(id, row.toolName(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    static final class G2ReplayStore implements ToolReplayStore {

        private final Map<String, ReplayRecord> records = new LinkedHashMap<>();

        @Override
        public void put(ReplayRecord record) {
            records.put(record.actionDigest(), record);
        }

        @Override
        public Optional<ReplayRecord> find(String actionDigest) {
            return Optional.ofNullable(records.get(actionDigest));
        }
    }

    static final class G2ReconcileSink implements IncidentSourceReconciler.AlertSource,
            IncidentSourceReconciler.ObservationSink {

        final Set<String> firing;
        final List<IncidentSourceReconciler.Observation> observations = new ArrayList<>();

        G2ReconcileSink(Set<String> firing) {
            this.firing = firing;
        }

        @Override
        public boolean isFiring(String alertFingerprint) {
            return firing.contains(alertFingerprint);
        }

        @Override
        public void append(IncidentSourceReconciler.Observation observation) {
            observations.add(observation);
        }
    }
}
