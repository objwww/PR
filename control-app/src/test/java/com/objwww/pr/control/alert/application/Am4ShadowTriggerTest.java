package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Am4ShadowTrigger 单测（M4-38 执行者工具面）：影子 run 镜像 holmes 身份
 * （同 incident/同 generation/同 investigation hash）+ DAG 三调查任务全 DONE →
 * 证据快照冻结 → REPORTING + 三源证据同快照盖章 + 影子零报告零发布 + stdout
 * 标记（{@code AM4_SHADOW_RUN_ID=} / {@code AM4_SHADOW_TASK=}）；降级案：
 * 单源 FAILED → 任务 DEAD、run 继续终 REPORTING、快照只含存活源成员。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class Am4ShadowTriggerTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
    private static final long GENERATION = 14L;
    private static final String TOOL_OK_JSON =
            "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},"
                    + "\"values\":[[1788781788,\"2\"]]}]}}";
    private static final String TOOL_ERROR_JSON = "{\"status\":\"error\",\"errorType\":\"x\"}";
    private static final byte[] TOOL_OK = TOOL_OK_JSON.getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOOL_ERROR = TOOL_ERROR_JSON.getBytes(StandardCharsets.UTF_8);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final TriggerEdgeStore edges = new TriggerEdgeStore();
    private final TriggerEvidence evidence = new TriggerEvidence();
    private final TriggerSnapshots snapshots = new TriggerSnapshots();
    private final TriggerLedger ledger = new TriggerLedger();
    private final TriggerClaims claims = new TriggerClaims();
    private final TriggerSlots slots = new TriggerSlots();
    private final RecorderStore comparisons = new RecorderStore();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shadowRunMirrorsHolmesAndRunsDagToReportingWithoutPublishing() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, incidentId, 14, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        ByteArrayOutputStream captured = captureStdout();
        UUID shadowId = trigger(inv -> ok(inv)).trigger(holmesId);
        resetStdout();

        assertThat(shadowId).isNotEqualTo(holmesId);
        RcaRun shadow = stores.runs.findById(shadowId).orElseThrow();
        assertThat(shadow.incidentId()).isEqualTo(incidentId);
        assertThat(shadow.generation()).isEqualTo(GENERATION);
        assertThat(shadow.investigationHash()).isEqualTo(snapshot);
        assertThat(shadow.state()).isEqualTo(RcaRunState.REPORTING);

        List<RcaTask> tasks = stores.tasks.findByRunId(shadowId);
        assertThat(tasks).extracting(RcaTask::taskKey)
                .containsExactlyInAnyOrder(Am4ShadowTrigger.TASK_METRICS,
                        Am4ShadowTrigger.TASK_LOGS, Am4ShadowTrigger.TASK_CHANGE);
        assertThat(tasks).allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DONE));

        assertThat(evidence.rows).hasSize(3);
        assertThat(evidence.rows).allSatisfy(e ->
                assertThat(e.scope().get("investigation_input_digest"))
                        .isEqualTo(snapshot.hex()));
        assertThat(evidence.rows).extracting(EvidenceEnvelope::source)
                .containsExactlyInAnyOrder("prometheus", "logs", "change");
        assertThat(ledger.succeeded).isEqualTo(3);

        assertThat(snapshots.byRun).hasSize(1);
        var frozen = snapshots.byRun.values().iterator().next().frozen();
        assertThat(frozen.observedGeneration()).isEqualTo(GENERATION);
        assertThat(snapshots.byRun.values().iterator().next().members()).hasSize(3);

        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains(Am4ShadowTrigger.RUN_ID_MARKER + shadowId)
                .contains(Am4ShadowTrigger.TASK_OUTCOME_MARKER + Am4ShadowTrigger.TASK_METRICS
                        + "=EVIDENCE_PRODUCED")
                .contains(Am4ShadowTrigger.TASK_OUTCOME_MARKER + "snapshot=");
    }

    @Test
    void failedSourceDegradesToDeadTaskAndSnapshotKeepsSurvivors() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 3, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        ByteArrayOutputStream captured = captureStdout();
        UUID shadowId = trigger(Am4ShadowTriggerTest::denyChange).trigger(holmesId);
        resetStdout();

        RcaRun shadow = stores.runs.findById(shadowId).orElseThrow();
        assertThat(shadow.state()).isEqualTo(RcaRunState.REPORTING);
        assertThat(taskState(shadowId, Am4ShadowTrigger.TASK_CHANGE))
                .isEqualTo(RcaTaskState.DEAD);
        assertThat(taskState(shadowId, Am4ShadowTrigger.TASK_METRICS))
                .isEqualTo(RcaTaskState.DONE);
        assertThat(evidence.rows).extracting(EvidenceEnvelope::source)
                .containsExactlyInAnyOrder("prometheus", "logs");
        assertThat(snapshots.byRun.values().iterator().next().members()).hasSize(2);
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains(Am4ShadowTrigger.TASK_OUTCOME_MARKER + Am4ShadowTrigger.TASK_CHANGE
                        + "=FAILED(REMOTE_UNAVAILABLE)");
    }

    @Test
    void policyDeniedSourceDegradesToDeadTaskInsteadOfEscaping() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 9, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        ByteArrayOutputStream captured = captureStdout();
        UUID shadowId = trigger(Am4ShadowTriggerTest::policyDenyChange).trigger(holmesId);
        resetStdout();

        // allowed-tools 裁剪 = Gateway 层抛 ToolControlPlaneException(POLICY_DENIED)：
        // 缺源降级语义要求任务 DEAD 续跑，异常不得逃出 trigger 把影子 run 永久挂在
        // QUEUED（195 实证：残留活跃 run 毒死同 incident 后续影子触发）
        RcaRun shadow = stores.runs.findById(shadowId).orElseThrow();
        assertThat(shadow.state()).isEqualTo(RcaRunState.REPORTING);
        assertThat(taskState(shadowId, Am4ShadowTrigger.TASK_CHANGE))
                .isEqualTo(RcaTaskState.DEAD);
        assertThat(evidence.rows).extracting(EvidenceEnvelope::source)
                .containsExactlyInAnyOrder("prometheus", "logs");
        assertThat(snapshots.byRun.values().iterator().next().members()).hasSize(2);
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains(Am4ShadowTrigger.RUN_ID_MARKER + shadowId)
                .contains(Am4ShadowTrigger.TASK_OUTCOME_MARKER + Am4ShadowTrigger.TASK_CHANGE
                        + "=FAILED(POLICY_DENIED)");
    }

    @Test
    void slotsHeldDuringShadowDriveAndReleasedAfterward() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 7, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        UUID shadowId = trigger(inv -> ok(inv)).trigger(holmesId);

        // 影子驱动期间占满 worker 槽位（195 实证：主容器 RcaWorker 与一次性实例
        // 抢同一影子 run 的任务 → transition CAS 失败 + Holmes 串味产报告），
        // 结束（含异常路径）全部归还
        assertThat(slots.acquired).isEqualTo(TriggerSlots.TOTAL);
        assertThat(slots.released).isEqualTo(TriggerSlots.TOTAL);
        assertThat(stores.runs.findById(shadowId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
    }

    @Test
    void shadowConclusionRecordedAgainstHolmesRunForEngineComparison() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 14, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        captureStdout();
        UUID shadowId = trigger(inv -> ok(inv)).trigger(holmesId);
        resetStdout();

        // M6-02 观察面成账：影子结论对照 holmes run 落 V32（对照行不是报告/发布）
        assertThat(comparisons.rows).hasSize(1);
        EngineComparisonRepository.ComparisonRow row = comparisons.rows.get(0);
        assertThat(row.nativeRunId()).isEqualTo(shadowId);
        assertThat(row.shadowExecRef()).isEqualTo("am4-shadow-trigger");
        assertThat(row.snapshotDigest()).isEqualTo(snapshot.hex());
        // holmes 侧无报告行（零发布纪律的镜像面）→ 诚实 report_missing，不臆造结论
        assertThat(row.holmesOutcome()).containsEntry("report_missing", true);
        // 双侧缺数维度不标记（缺数≠差异；无 GT 不判对错）
        assertThat(row.disagreeFlags()).isEmpty();
        // comparison_key = sha256(holmes\nnative\nsnapshot\n候选 digest)（无 bundle = 空串）
        assertThat(row.comparisonKey()).isEqualTo(Digest.sha256Of(
                holmesId + "\n" + shadowId + "\n" + snapshot.hex() + "\n" + "").hex());
    }

    // ------------------------------------------------------------------ 组装

    private Am4ShadowTrigger trigger(ToolInvoker gateway) {
        DeterministicSupervisor supervisor = new DeterministicSupervisor(
                new PlanCompiler(planAgents(), stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, planAgents(), inPlaceTx(), () -> NOW);
        ToolRegistry tools = new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        MetricsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_OK)),
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_OK)),
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_OK))));
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                tools, gateway, evidence, ledger, mapper);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                tools, gateway, evidence, ledger, mapper);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                tools, gateway, evidence, ledger, mapper);
        NativeRcaAgent nativeRca = new NativeRcaAgent(evidence, snapshots, claims,
                new ClaimReducer(Set.of("holmes", "prometheus"), "ut-policy"));
        EngineComparisonRecorder recorder = new EngineComparisonRecorder(
                new NoBundles(), stores.runs, stores.reports, claims, comparisons,
                com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP);
        return new Am4ShadowTrigger(supervisor, stores.runs, stores.tasks, evidence,
                snapshots, metrics, logs, change, nativeRca, slots,
                Am4ShadowTrigger.WORKER_SLOT_SCOPE, () -> NOW, recorder);
    }

    /** 全部执行成功：恒回 EXECUTED + success 体 */
    private static ToolGateway.ToolInvocationResult ok(ToolGateway.ToolInvocation invocation) {
        return new ToolGateway.ToolInvocationResult(
                ToolGateway.ToolInvocationResult.Kind.EXECUTED, "ut-digest", TOOL_OK);
    }

    /** change.query 源降级：恒回 error 体（REMOTE_4XX → FAILED） */
    private static ToolGateway.ToolInvocationResult denyChange(
            ToolGateway.ToolInvocation invocation) {
        byte[] body = "change.query".equals(invocation.toolName()) ? TOOL_ERROR : TOOL_OK;
        return new ToolGateway.ToolInvocationResult(
                ToolGateway.ToolInvocationResult.Kind.EXECUTED, "ut-digest", body);
    }

    /** change.query 被 allowed-tools 裁剪：Gateway 层直接拒绝（POLICY_DENIED 异常） */
    private static ToolGateway.ToolInvocationResult policyDenyChange(
            ToolGateway.ToolInvocation invocation) {
        if ("change.query".equals(invocation.toolName())) {
            throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                    "tool 未在 allowed-tools 白名单: change.query");
        }
        return ok(invocation);
    }

    private RcaTaskState taskState(UUID runId, String key) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow().state();
    }

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOut;

    private ByteArrayOutputStream captureStdout() {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        return capturedOut;
    }

    private void resetStdout() {
        System.setOut(originalOut);
    }

    private static AgentProfile profile(String name, String toolName) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(toolName), Map.of(BudgetKind.TOOL_CALL, 4L), Map.of("type", "object"));
    }

    private static AgentRegistry planAgents() {
        return new AgentRegistry(List.of(
                new AgentProfile("metrics", "1", "prompt-m", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("logs", "1", "prompt-l", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("change", "1", "prompt-c", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"))));
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    // ------------------------------------------------------------------ 内存件

    /** 固定槽位池（TOTAL 槽；acquire/release 调用计数供占槽断言） */
    private static final class TriggerSlots implements SchedulerSlotRepository {

        static final int TOTAL = 2;

        private int acquired;
        private int released;

        @Override
        public Optional<AcquiredSlot> tryAcquire(String scope, String owner, UUID taskId,
                Instant now, Duration lease) {
            if (acquired >= TOTAL) {
                return Optional.empty();
            }
            acquired++;
            return Optional.of(new AcquiredSlot(acquired, acquired));
        }

        @Override
        public boolean release(String scope, int slotNo, String owner, long leaseEpoch) {
            released++;
            return true;
        }

        @Override
        public void heartbeat(String scope, int slotNo, String owner, long leaseEpoch,
                Instant now, Duration extend) {
            // 影子占槽租约一次给足，测试面不消费心跳
        }

        @Override
        public long reclaimExpired(Instant now) {
            return 0;
        }

        @Override
        public List<Integer> occupiedSlots(String scope) {
            return List.of();
        }

        @Override
        public int totalSlots(String scope) {
            return TOTAL;
        }
    }

    private static final class TriggerEdgeStore implements TaskEdgeRepository {

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

    private static final class TriggerEvidence implements EvidenceRepository {

        private final List<EvidenceEnvelope> rows = new ArrayList<>();

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

    private static final class TriggerSnapshots implements EvidenceSnapshotRepository {

        private final Map<UUID, Frozen> byRun = new ConcurrentHashMap<>();
        private final Map<UUID, List<SnapshotMemberRow>> bySnapshotId = new ConcurrentHashMap<>();

        private record Frozen(FrozenSnapshot frozen, List<SnapshotMemberRow> members) {
        }

        @Override
        public boolean freeze(FrozenSnapshot snapshot, List<SnapshotMemberRow> members) {
            byRun.put(snapshot.runId(), new Frozen(snapshot, List.copyOf(members)));
            bySnapshotId.put(snapshot.snapshotId(), List.copyOf(members));
            return true;
        }

        /** EX-A4a（F05）：find 真实按 (run,digest) 解析——黑板=成员表由 agent 消费 */
        @Override
        public Optional<FrozenSnapshot> find(UUID runId, String snapshotDigest) {
            return byRun.values().stream()
                    .filter(f -> f.frozen().runId().equals(runId)
                            && f.frozen().snapshotDigest().equals(snapshotDigest))
                    .map(Frozen::frozen)
                    .findFirst();
        }

        @Override
        public List<SnapshotMemberRow> membersOf(UUID snapshotId) {
            return bySnapshotId.getOrDefault(snapshotId, List.of());
        }
    }

    private static final class TriggerLedger implements RcaToolInvocationLedger {

        private int succeeded;

        @Override
        public void open(InvocationIdentity identity) {
            // 只统计终态；PENDING 行本测试不消费
        }

        @Override
        public boolean succeed(UUID operationId) {
            succeeded++;
            return true;
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return true;
        }
    }

    private static final class TriggerClaims implements ClaimStore {

        private final List<ClaimVerdict> appended = new ArrayList<>();

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
            return List.of();
        }
    }

    /** 对照落账捕集（V32 观察面断言面） */
    private static final class RecorderStore implements EngineComparisonRepository {

        private final List<EngineComparisonRepository.ComparisonRow> rows = new ArrayList<>();

        @Override
        public boolean append(EngineComparisonRepository.ComparisonRow row) {
            rows.add(row);
            return true;
        }

        @Override
        public List<EngineComparisonRepository.ComparisonRow> findByNativeRunId(UUID runId) {
            return rows.stream().filter(r -> r.nativeRunId().equals(runId)).toList();
        }
    }

    /** 无激活 bundle（候选 digest 记空串——comparison_key 输入面诚实为空） */
    private static final class NoBundles
            implements com.objwww.pr.control.release.domain.repository.ConfigBundleRepository {

        @Override
        public long nextRevision() {
            return 1;
        }

        @Override
        public boolean insert(
                com.objwww.pr.control.release.domain.model.ConfigBundle bundle) {
            return true;
        }

        @Override
        public java.util.Optional<com.objwww.pr.control.release.domain.model.ConfigBundle>
                findByDigest(Digest digest) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Digest> activeDigest() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.objwww.pr.control.release.domain.repository.ConfigBundleRepository.ActivePointer>
                findActivePointer() {
            return java.util.Optional.empty();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return true;
        }
    }
}
