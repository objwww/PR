package com.objwww.pr.control.infrastructure.nativeexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.FallbackService;
import com.objwww.pr.control.alert.application.IncidentProjector;
import com.objwww.pr.control.alert.application.NativeReportAdapter;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
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
 * NATIVE_INVESTIGATE driver task 后由本执行器驱动 Native 全链——提案读 active
 * bundle {@code native.proposal} 段（缺失 fail-closed，非法经 Supervisor
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
                replayRegistry(), runner, evidence, new TestLedger(), MAPPER);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, new TestLedger(), MAPPER);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, new TestLedger(), MAPPER);
        NativeRcaAgent nativeRcaAgent = new NativeRcaAgent(evidence, claims, reducer);

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
                clock, com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP);
        orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls,
                new ReportCompletedNotifier(stores.publications, stores.outboxes,
                        List.of("test"), "m6-candidate-v1", 280),
                stores.cas, com.objwww.pr.control.alert.domain.service.SlaPolicy.defaults(),
                clock, "rca", com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                com.objwww.pr.control.release.application.CanaryRouter.holmesOnly(),
                new FallbackService(stores.runs, stores.incidents, stores.tasks,
                        stores.rcaEvents, stores.fallbacks,
                        com.objwww.pr.control.alert.domain.service.SlaPolicy.defaults(),
                        clock, com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                        true, 20),
                stores.winners);
        incidentId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=checkout",
                IncidentStatus.FIRING, 0, NOW, NOW, null, null, null, 0, 0, 0, null,
                NOW, NOW, NOW, NOW));
    }

    // ------------------------------------------------------------------ 全链

    @Test
    @DisplayName("全链：提案落图→驱动（回放 MISS 降级 DEAD）→快照→REPORTING→报告 engine=NATIVE")
    void fullChainProducesNativeReport() {
        UUID runId = castNativeRun();
        bundles.publish(nativeBundle(proposal()));
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
                .isEqualTo(Digest.sha256Of("bundle-v1").hex());
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
    @DisplayName("提案缺失 fail-closed：无 active bundle → PROPOSAL_MISSING 终态，run 不被驱动")
    void proposalMissingFailsClosed() {
        UUID runId = castNativeRun();

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
        UUID runId = castNativeRun();
        bundles.publish(nativeBundle(Map.of("schema_version", "bogus", "tasks", List.of(),
                "edges", List.of())));

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

    // ------------------------------------------------------------------ 夹具

    /** 铸 NATIVE run + driver task（模拟投影铸造点产物；路由四列随行） */
    private UUID castNativeRun() {
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, GENERATION, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null, null, null);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle-v1"), "g:checkout", 42, "BUCKETED_NATIVE"));
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId,
                RcaTask.taskKeyFor(RcaEngine.NATIVE), RcaTaskState.READY, 5, NOW, NOW,
                Instant.MAX, null, null, 0, 0, 3, NOW, NOW));
        // 模拟 worker 领取（finishTask 租约栅栏的当前租约锚：owner+epoch）
        RcaTask driver = stores.tasks.findByRunId(runId).get(0);
        stores.tasks.update(new RcaTask(driver.id(), driver.runId(), driver.taskKey(),
                RcaTaskState.LEASED, driver.priority(), driver.availableAt(),
                driver.readySince(), driver.deadlineAt(), "worker-a",
                NOW.plus(Duration.ofMinutes(5)), 0, 1, driver.maxAttempts(),
                driver.createdAt(), NOW));
        return runId;
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

        void publish(Map<String, Object> content) {
            ConfigBundle bundle = ConfigBundle.of(content, "op", NOW);
            rows.add(bundle);
            active = bundle.bundleDigest();
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
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
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

        @Override
        public boolean freeze(FrozenSnapshot snapshot, List<SnapshotMemberRow> members) {
            frozen.add(snapshot);
            return true;
        }

        @Override
        public Optional<FrozenSnapshot> find(UUID runId, String snapshotDigest) {
            return frozen.stream().filter(s -> s.runId().equals(runId)
                    && s.snapshotDigest().equals(snapshotDigest)).findFirst();
        }

        @Override
        public List<SnapshotMemberRow> membersOf(UUID snapshotId) {
            return List.of();
        }
    }

    static final class TestLedger implements RcaToolInvocationLedger {
        @Override
        public void open(InvocationIdentity identity) {
        }

        @Override
        public boolean succeed(UUID operationId) {
            return true;
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return true;
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
