package com.objwww.pr.control.it;

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
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.dag.PlanProposal;
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
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.nativeexec.NativeInvestigationExecutor;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresDelegationDecisionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceSnapshotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPrimaryCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskExecutionBindingRepository;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-01 IT 案①（落码方案 §M6-01 测试清单 IT 面，195 真 PG 补证）：
 * <b>Native 全链</b> run→任务→Claim→报告→publication/outbox，engine=NATIVE 可辨识——
 * driver 任务经 C-70 通用领取面真实领取（claimNext 只认 driver 键），执行器驱动
 * 三调查任务（回放 MISS = 缺源降级 DEAD 不阻断报告）→ 冻结证据快照
 * （configDigest = 路由 bundle digest）→ advance 入 REPORTING → NativeRcaAgent →
 * ReportAssembler → NativeReportAdapter 过自家验证链 → finishTask 共用出口落
 * 报告/发布/通知（FUT-49）。本机无 docker 自动跳过（PostgresITBase 惯例）。
 */
class Am6NativeFullChainIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY_VERSION = "policy-2026-09";
    private static final String WORKER = "it-worker";
    private static final String TOOL_REGISTRY_DIGEST = "am6-native-it:metrics,logs,change";
    private static final int GENERATION = 0;

    private PostgresRcaTaskRepository tasks;
    private PostgresRcaRunRepository runs;
    private PostgresIncidentRepository incidents;
    private PostgresEvidenceRepository evidence;
    private PostgresClaimStore claims;
    private PostgresConfigBundleRepository bundles;
    private NativeInvestigationExecutor executor;
    private RcaRunOrchestrator orchestrator;
    private PostgresRcaAttemptRepository attempts;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        evidence = new PostgresEvidenceRepository(jdbc, controlTx, MAPPER);
        attempts = new PostgresRcaAttemptRepository(jdbc);
        claims = claimStore();
        bundles = new PostgresConfigBundleRepository(controlDataSource());
        // config 族两表自清（BA-41：基座 TRUNCATE 清单刻意排除 V24 种子行依赖面）；
        // 删后必须回种 id=1（BA-42②：激活 CAS 的 WHERE id=1 依赖种子行在场）
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        ClaimReducer reducer = new ClaimReducer(Set.of(), POLICY_VERSION);
        PostgresRcaToolInvocationLedger ledger = new PostgresRcaToolInvocationLedger(
                jdbc, controlTx);
        AgentReplayRunner runner = new AgentReplayRunner(
                new ReplayToolGateway(replayRegistry(), new NoReplayStore()));
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, ledger, MAPPER);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, ledger, MAPPER);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                replayRegistry(), runner, evidence, ledger, MAPPER);
        NativeRcaAgent nativeRcaAgent = new NativeRcaAgent(evidence,
                new PostgresEvidenceSnapshotRepository(jdbc, controlTx), claims, reducer);

        DeterministicSupervisor supervisor = new DeterministicSupervisor(
                new PlanCompiler(agentRegistry(), tasks,
                        new PostgresTaskEdgeRepository(jdbc),
                        new PostgresTaskExecutionBindingRepository(jdbc, MAPPER), controlTx),
                new DagExecutionService(new PostgresTaskEdgeRepository(jdbc), tasks),
                runs, tasks,
                new PostgresTaskExecutionBindingRepository(jdbc, MAPPER),
                new PostgresPrimaryCheckpointRepository(jdbc, MAPPER),
                new PostgresDelegationDecisionRepository(jdbc),
                agentRegistry(), controlTx, AlertClock.system());

        executor = new NativeInvestigationExecutor(bundles, supervisor, tasks, runs,
                evidence, new PostgresEvidenceSnapshotRepository(jdbc, controlTx),
                nativeRcaAgent, claims,
                new EvidencePackageValidator(65_536, 32, 4_096),
                TOOL_REGISTRY_DIGEST,
                AlertClock.system(), AlertMetrics.NOOP,
                new RunBudgetGate(new InMemoryRunBudgetLedger()),
                // EX-A1：本件焦点非预算面，宽限额只保证 openRun/TOOL_CALL 硬闸不误伤全链
                Map.of(BudgetKind.STEP, 256L, BudgetKind.TOOL_CALL, 256L,
                        BudgetKind.EVIDENCE, 256L, BudgetKind.SUBTASK, 64L),
                // EX-A3：恢复 checkpoint 读面（真 PG 账本）
                ledger,
                // R7-X2：分派面 = 持久绑定 + 兼容适配运行器目录
                new PostgresTaskExecutionBindingRepository(jdbc, MAPPER), agentRegistry(),
                compatRunners(metrics, logs, change),
                new PostgresPrimaryCheckpointRepository(jdbc, MAPPER),
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresRcaModelCallLedger(jdbc, MAPPER, controlTx), null);
        orchestrator = new RcaRunOrchestrator(tasks, runs, attempts,
                new PostgresRcaReportRepository(jdbc), incidents,
                new PostgresSchedulerSlotRepository(jdbc),
                new PostgresInvestigationResultRepository(jdbc),
                new PostgresRcaToolCallRepository(jdbc),
                new ReportCompletedNotifier(
                        new PostgresReportPublicationRepository(jdbc),
                        new PostgresNotifyOutboxRepository(jdbc),
                        List.of("test"), "am6-native-it", 280),
                new AlertInMemoryStores.Cas(), SlaPolicy.defaults(),
                AlertClock.system(), "rca", AlertMetrics.NOOP,
                com.objwww.pr.control.release.application.CanaryRouter.holmesOnly(),
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresReportWinnerRepository(jdbc));
    }

    // ------------------------------------------------------------------ 案① 全链

    @Test
    void fullChainRunToPublicationEngineNativeIdentifiable() {
        Digest bundleDigest = publishNativeBundle(proposal());
        UUID incidentId = insertIncident("full-chain");
        UUID runId = castNativeRun(incidentId, bundleDigest);
        RcaTask driver = claimDriver(runId);
        evidence.insert(annotated(runId, driver.id(), "prometheus", "cpu over 95%"));
        evidence.insert(annotated(runId, driver.id(), "logs", "cpu over 95%"));

        RcaAttempt attempt = startedAttempt(driver);
        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                runs.findById(runId).orElseThrow(),
                incidents.findById(incidentId).orElseThrow(),
                attempt, () -> { });

        assertThat(result.outcome())
                .as("全链执行成功").isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        assertThat(result.artifact().orElseThrow().validationStatus())
                .isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);

        // 链推进面：DAG 任务全 DEAD（回放 MISS 缺源降级）、run 入 REPORTING、
        // 快照以路由 bundle digest 冻结、双源断言归并为 1 条 ACTIVE Claim
        assertThat(tasks.findByRunId(runId))
                .filteredOn(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DEAD));
        assertThat(runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
        assertThat(adminJdbc.sql("SELECT config_digest FROM rca_evidence_snapshot "
                        + "WHERE run_id = :run").param("run", runId)
                .query((rs, i) -> rs.getString("config_digest")).single())
                .isEqualTo(bundleDigest.hex());
        assertThat(adminJdbc.sql("SELECT count(*) FROM rca_snapshot_member m "
                        + "JOIN rca_evidence_snapshot s ON s.id = m.snapshot_id "
                        + "WHERE s.run_id = :run").param("run", runId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(claims.findByRunId(runId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(
                            com.objwww.pr.control.alert.domain.claim.ClaimStatus.TRUE);
                    assertThat(row.lifecycle()).isEqualTo(ClaimLifecycle.ACTIVE);
                });

        // 收尾复用 finishTask：报告 + 发布 + outbox 同链落库（FUT-49 共用出口）
        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(driver,
                WORKER, -1, -1, result, attempt);
        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
        assertThat(runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);

        // engine=NATIVE 可辨识：DB 权威列 + 报告原文双重锚
        assertThat(adminJdbc.sql("SELECT engine FROM rca_run WHERE id = :id")
                .param("id", runId).query(String.class).single()).isEqualTo("NATIVE");
        assertThat(count("rca_report")).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT raw_text FROM rca_report WHERE run_id = :run")
                .param("run", runId).query(String.class).single()).contains("NATIVE");
        assertThat(count("report_publication")).isEqualTo(1);
        assertThat(count("notify_outbox")).isEqualTo(1);
        assertThat(tasks.findById(driver.id()).orElseThrow().state())
                .isEqualTo(RcaTaskState.DONE);
    }

    // --------------------------------------------- 提案缺失 fail-closed（真 PG 面）

    @Test
    void proposalMissingFailsClosedOnRealStack() {
        UUID incidentId = insertIncident("no-bundle");
        UUID runId = castNativeRun(incidentId, Digest.sha256Of("unpublished"));
        RcaTask driver = claimDriver(runId);

        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                runs.findById(runId).orElseThrow(),
                incidents.findById(incidentId).orElseThrow(),
                startedAttempt(driver), () -> { });

        assertThat(result.outcome())
                .isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.FAILED_TERMINAL);
        assertThat(result.errorClass()).isEqualTo("PROPOSAL_MISSING");
        // 零驱动：run 仍 QUEUED、零 DAG 任务、零快照、零 Claim
        assertThat(runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.QUEUED);
        assertThat(tasks.findByRunId(runId)).hasSize(1);
        assertThat(count("rca_evidence_snapshot")).isZero();
        assertThat(count("rca_claim")).isZero();
    }

    // ------------------------------------------------------------------ 种子与组装

    /** 发布并激活含 native.proposal 段的 bundle，返回其 digest（路由快照身份面） */
    private Digest publishNativeBundle(Map<String, Object> proposal) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", POLICY_VERSION);
        content.put("native", Map.of("proposal", proposal));
        ConfigBundle bundle = ConfigBundle.of(content, "it-operator", Instant.now());
        assertThat(bundles.insert(bundle)).isTrue();
        grantPassQualification(bundle.bundleDigest());
        assertThat(bundles.activateQualified(bundle.bundleDigest(), 0L, "it-operator",
                Instant.now())).isTrue();
        return bundle.bundleDigest();
    }

    private UUID insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        return id;
    }

    /** 铸 NATIVE run + driver 任务（生产铸造点形态：路由四列随行落库） */
    private UUID castNativeRun(UUID incidentId, Digest bundleDigest) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        runs.insertRouted(new RcaRun(runId, incidentId, GENERATION, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("material-" + runId), now, now,
                null, null, null), new RcaRunRouting(RcaEngine.NATIVE, bundleDigest,
                "alertname=HighErrorRate|service=checkout", 42, "BUCKETED_NATIVE"));
        tasks.insert(new RcaTask(UUID.randomUUID(), runId,
                RcaTask.taskKeyFor(RcaEngine.NATIVE), RcaTaskState.READY, 5, now, now,
                now.plusSeconds(600), null, null, 0, 0, 3, now, now));
        return runId;
    }

    /** C-70 真领取面：claimNext 只认 driver 键——NATIVE driver 必须可领 */
    private RcaTask claimDriver(UUID runId) {
        Optional<RcaTask> claimed = tasks.claimNext(WORKER, Instant.now(),
                Duration.ofMinutes(5));
        assertThat(claimed).as("NATIVE_INVESTIGATE driver 经通用领取面可领").isPresent();
        RcaTask driver = claimed.orElseThrow();
        assertThat(driver.taskKey()).isEqualTo(RcaTask.NATIVE_INVESTIGATE);
        assertThat(driver.runId()).isEqualTo(runId);
        return driver;
    }

    private RcaAttempt startedAttempt(RcaTask driver) {
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), driver.id(), 1,
                (int) driver.leaseEpoch(), WORKER, RcaAttemptStatus.STARTED, null, null,
                null, Instant.now(), null, null);
        attempts.insert(attempt);
        return attempt;
    }

    /** 双源断言注记证据（claim 注记驱动 Claim 面；代际 = run 代） */
    private EvidenceEnvelope annotated(UUID runId, UUID taskId, String source,
            String reason) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("claim_key", "cpu_saturation");
        scope.put("claim_status", "TRUE");
        scope.put("reason", reason);
        scope.put("scope", "svc-a");
        scope.put("time_range", "09:50/10:00");
        return EvidenceEnvelope.create(UUID.randomUUID(), runId, taskId,
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, GENERATION, source,
                scope, null, null,
                Map.of("status", "success", "data", Map.of("result", List.of("x"))));
    }

    private PostgresClaimStore claimStore() {
        PostgresRcaEventAppender events = new PostgresRcaEventAppender(
                JdbcClient.create(controlDataSource()), controlTx,
                new TransactionTemplate(
                        new DataSourceTransactionManager(controlDataSource())));
        return new PostgresClaimStore(JdbcClient.create(controlDataSource()), controlTx,
                MAPPER, events);
    }

    /** 固定提案：三调查任务全并行（零边 = 全根任务，与 UT 冻结提案同形） */
    private static Map<String, Object> proposal() {
        List<Map<String, Object>> planTasks = List.of(
                Map.of("key", "investigate-metrics", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "investigate-logs", "type", "logs@1", "inputs", List.of()),
                Map.of("key", "investigate-change", "type", "change@1", "inputs", List.of()));
        return Map.of("schema_version", PlanProposal.SCHEMA_VERSION,
                "tasks", planTasks, "edges", List.of());
    }

    private static AgentProfile profile(String name, String tool) {
        return new AgentProfile(name, "1", "prompt-" + name, POLICY_VERSION, Set.of(tool),
                Map.of(BudgetKind.TOOL_CALL, 4L), Map.of("type", "object"));
    }

    private static AgentRegistry agentRegistry() {
        return new AgentRegistry(List.of(
                new AgentProfile("metrics", "1", "prompt-m", POLICY_VERSION, Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("logs", "1", "prompt-l", POLICY_VERSION, Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("change", "1", "prompt-c", POLICY_VERSION, Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"))));
    }

    /** R7-X2：兼容适配运行器目录（role→Agent 映射，与生产装配 AlertFlowConfig 同形） */
    private static com.objwww.pr.control.alert.application.agent.RunnerDirectory compatRunners(
            MetricsAgent metrics, LogsAgent logs, ChangeAgent change) {
        Map<String, com.objwww.pr.control.alert.application.agent.SingleToolRoleRunner.RoleQueryHandler>
                handlers = new java.util.LinkedHashMap<>();
        handlers.put("metrics", (ctx, start, end) -> metrics.investigate(ctx,
                new MetricsAgent.MetricsQuery(
                        "oa_duplicate_orders_current{job=\"order-arena\"}", start, end,
                        com.objwww.pr.control.alert.domain.identity.InvestigationInputs.STEP)));
        handlers.put("logs", (ctx, start, end) -> logs.investigate(ctx,
                new LogsAgent.LogsQuery(start, end)));
        handlers.put("change", (ctx, start, end) -> change.investigate(ctx,
                new ChangeAgent.ChangeQuery(start, end)));
        return new com.objwww.pr.control.alert.application.agent.RunnerDirectory(List.of(
                new com.objwww.pr.control.alert.application.agent.SingleToolRoleRunner(
                        handlers)));
    }

    private static ToolRegistry replayRegistry() {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(MetricsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}"
                                .getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(LogsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}"
                                .getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(ChangeAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor("{\"status\":\"success\"}"
                                .getBytes(StandardCharsets.UTF_8)))));
    }

    /** 回放恒 MISS（缺源降级路径的确定性驱动源） */
    private static final class NoReplayStore implements ToolReplayStore {
        @Override
        public void put(ReplayRecord record) {
        }

        @Override
        public Optional<ReplayRecord> find(String actionDigest) {
            return Optional.empty();
        }
    }
}
