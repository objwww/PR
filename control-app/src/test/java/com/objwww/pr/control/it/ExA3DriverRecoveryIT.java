package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
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
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.infrastructure.nativeexec.NativeInvestigationExecutor;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceSnapshotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunBudgetLedger;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-A3 可恢复驱动 IT（L1 真 PG；本机无 docker 自动跳过，195 部署段实跑，
 * docs/告警-EXA3-可恢复驱动.md §4）：
 *
 * <p>V38 checkpoint 面——result_ref 列/FK/恢复读索引在库；markResultRef CAS 锚
 * PENDING、幽灵引用（不存在的证据 id）在 FK 面被拒（BA-60 同律：引用完整性
 * 不靠 Java 侧自觉）；findRecoveryByTask 投影按 call_seq 序返回四态行。
 *
 * <p>恢复驱动面——真 PG 账本行上命中阶段③（SUCCESS+result_ref 幂等收尾，
 * 零触网零新行）与阶段②（PENDING 悬挂 → UNKNOWN 归档 + 新物理请求重驱成对，
 * 预算占用 PROVISIONAL 保留、call_seq 跨 attempt 单调）；恢复不重复调用账本可证。
 */
class ExA3DriverRecoveryIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY_VERSION = "policy-exa3";
    private static final String WORKER = "it-worker";
    private static final String TOOL_REGISTRY_DIGEST = "exa3-it:metrics,logs,change";
    private static final int GENERATION = 0;
    /** 证据产出型固定响应（三 Agent 同源；与 UT executorWithEvidenceProducingTools 同形） */
    private static final byte[] SUCCESS_WITH_SERIES =
            "{\"status\":\"success\",\"data\":{\"result\":[{\"x\":1}]}}"
                    .getBytes(StandardCharsets.UTF_8);

    private JdbcClient jdbc;
    private PostgresRcaTaskRepository tasks;
    private PostgresRcaRunRepository runs;
    private PostgresIncidentRepository incidents;
    private PostgresEvidenceRepository evidence;
    private PostgresConfigBundleRepository bundles;
    private PostgresRcaAttemptRepository attempts;
    private PostgresRcaToolInvocationLedger ledger;
    private PostgresRunBudgetLedger budget;
    private NativeInvestigationExecutor executor;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        evidence = new PostgresEvidenceRepository(jdbc, controlTx, MAPPER);
        attempts = new PostgresRcaAttemptRepository(jdbc);
        bundles = new PostgresConfigBundleRepository(controlDataSource());
        ledger = new PostgresRcaToolInvocationLedger(jdbc, controlTx);
        budget = new PostgresRunBudgetLedger(jdbc, controlTx);
        // config 族两表自清（BA-41/BA-42② 惯例，Am6NativeFullChainIT 同形）
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        PostgresClaimStore claims = claimStore();
        // 证据产出型固定工具面：直挂 ToolInvoker（AgentReplayRunner 的 REPLAY_MISS
        // 绝不降级活执行，会让三任务全 DEAD——B-20；与 UT 执行器夹具同形）
        com.objwww.pr.control.alert.application.tool.ToolInvoker fixedTools =
                invocation -> new com.objwww.pr.control.alert.application.tool.ToolGateway
                        .ToolInvocationResult(
                        com.objwww.pr.control.alert.application.tool.ToolGateway
                                .ToolInvocationResult.Kind.EXECUTED, "fixed",
                        SUCCESS_WITH_SERIES);
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, ledger, MAPPER);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, ledger, MAPPER);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                replayRegistry(), fixedTools, evidence, ledger, MAPPER);
        DeterministicSupervisor supervisor = new DeterministicSupervisor(
                new PlanCompiler(agentRegistry(), tasks,
                        new com.objwww.pr.control.infrastructure.persistence
                                .PostgresTaskEdgeRepository(jdbc),
                        new com.objwww.pr.control.infrastructure.persistence
                                .PostgresTaskExecutionBindingRepository(jdbc, MAPPER),
                        controlTx),
                new DagExecutionService(new com.objwww.pr.control.infrastructure.persistence
                        .PostgresTaskEdgeRepository(jdbc), tasks),
                runs, tasks,
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresTaskExecutionBindingRepository(jdbc, MAPPER),
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresPrimaryCheckpointRepository(jdbc, MAPPER),
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresDelegationDecisionRepository(jdbc),
                agentRegistry(), controlTx, AlertClock.system());
        executor = new NativeInvestigationExecutor(bundles, supervisor, tasks, runs,
                evidence, new PostgresEvidenceSnapshotRepository(jdbc, controlTx),
                new NativeRcaAgent(evidence,
                        new PostgresEvidenceSnapshotRepository(jdbc, controlTx), claims,
                        new ClaimReducer(Set.of(), POLICY_VERSION)),
                claims, new com.objwww.pr.control.alert.domain.service.EvidencePackageValidator(
                        65_536, 32, 4_096),
                TOOL_REGISTRY_DIGEST,
                AlertClock.system(), AlertMetrics.NOOP,
                new RunBudgetGate(new com.objwww.pr.control.alert.infrastructure
                        .InMemoryRunBudgetLedger()),
                Map.of(BudgetKind.STEP, 256L, BudgetKind.TOOL_CALL, 256L,
                        BudgetKind.EVIDENCE, 256L, BudgetKind.SUBTASK, 64L),
                ledger,
                // R7-X2：分派面 = 持久绑定 + 兼容适配运行器目录
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresTaskExecutionBindingRepository(jdbc, MAPPER),
                agentRegistry(), compatRunners(metrics, logs, change),
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresPrimaryCheckpointRepository(jdbc, MAPPER), null);
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

    // --------------------------------------------- V38 schema 面

    @Test
    void v38ResultRefColumnForeignKeyAndRecoveryIndexExist() {
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'rca_tool_invocation' AND column_name = 'result_ref'
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                         WHERE tablename = 'rca_tool_invocation'
                           AND indexname = 'idx_rca_tool_invocation_run_task'
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM information_schema.table_constraints
                         WHERE table_name = 'rca_tool_invocation'
                           AND constraint_name = 'fk_rca_tool_invocation_result_ref'
                        """).query(Long.class).single()).isEqualTo(1);
    }

    // --------------------------------------------- 阶段③：result_ref 幂等收尾

    @Test
    void stage3ResultRefCompletesTaskWithoutNewEvidenceOrLedgerRows() {
        UUID runId = fullChainOnce("stage3");

        // 崩溃窗：SUCCESS 收据在账但 metrics 任务残留 RUNNING（收尾前被杀）
        UUID metricsTask = dagTaskId(runId, "investigate-metrics");
        adminJdbc.sql("UPDATE rca_task SET state = 'RUNNING' WHERE id = :id")
                .param("id", metricsTask).update();
        int evidenceBefore = evidence.findByRunId(runId).size();
        long rowsBefore = invocationRowCount(runId);
        UUID resultRefBefore = adminJdbc.sql(
                        "SELECT result_ref FROM rca_tool_invocation WHERE run_id = :run "
                                + "AND task_id = :task").param("run", runId)
                .param("task", metricsTask).query(UUID.class).single();

        RcaTask driver = driverOf(runId);
        RcaTaskExecutor.ExecutionResult second = executor.execute(driver,
                runs.findById(runId).orElseThrow(),
                incidents.findById(incidentOf(runId)).orElseThrow(),
                startedAttempt(driver, 2), () -> { });

        assertThat(second.outcome()).isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        assertThat(taskState(metricsTask)).as("阶段③：result_ref 幂等收尾 DONE")
                .isEqualTo(RcaTaskState.DONE);
        assertThat(evidence.findByRunId(runId)).as("零新证据（零触网）")
                .hasSize(evidenceBefore);
        assertThat(invocationRowCount(runId)).as("零新账本行（不重复调用，账本可证）")
                .isEqualTo(rowsBefore);
        assertThat(adminJdbc.sql(
                        "SELECT result_ref FROM rca_tool_invocation WHERE run_id = :run "
                                + "AND task_id = :task").param("run", runId)
                .param("task", metricsTask).query(UUID.class).single())
                .isEqualTo(resultRefBefore);
        // checkpoint 引用可解析：阶段③判定输入（SUCCESS 行 + 在库证据）真 PG 面
        assertThat(evidence.findById(resultRefBefore)).isPresent();
    }

    // --------------------------------------------- 阶段②：悬挂 PENDING → UNKNOWN + 新物理请求成对

    @Test
    void stage2PendingOrphanArchivedUnknownAndRedrivenAsNewBudgetedPair() {
        UUID runId = fullChainOnce("stage2");
        UUID metricsTask = dagTaskId(runId, "investigate-metrics");
        UUID attempt1 = adminJdbc.sql("""
                        SELECT DISTINCT i.attempt_id FROM rca_tool_invocation i
                         WHERE i.run_id = :run AND i.task_id = :task
                        """).param("run", runId).param("task", metricsTask)
                .query(UUID.class).single();

        // 崩溃窗手术：invoke 在途被杀——删 SUCCESS 收据、留悬挂 PENDING 行
        //（call_seq=4，既有最大 3 之上）、PROVISIONAL 预算占用（发送资格已取得）
        adminJdbc.sql("UPDATE rca_task SET state = 'RUNNING' WHERE id = :id")
                .param("id", metricsTask).update();
        adminJdbc.sql("DELETE FROM rca_tool_invocation WHERE run_id = :run "
                + "AND task_id = :task").param("run", runId).param("task", metricsTask)
                .update();
        UUID orphanOp = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(orphanOp, runId,
                metricsTask, attempt1, 4, MetricsAgent.TOOL_NAME, "1",
                Digest.sha256Of("orphan-action").hex()));
        budget.ensureLimit(runId, BudgetKind.TOOL_CALL, 16);
        ReservationKey orphanKey = new ReservationKey(runId, metricsTask, attempt1, 4,
                BudgetKind.TOOL_CALL);
        assertThat(budget.reserve(orphanKey, 1).allowed()).isTrue();
        budget.provisional(orphanKey);
        int evidenceBefore = evidence.findByRunId(runId).size();

        RcaTask driver = driverOf(runId);
        RcaTaskExecutor.ExecutionResult second = executor.execute(driver,
                runs.findById(runId).orElseThrow(),
                incidents.findById(incidentOf(runId)).orElseThrow(),
                startedAttempt(driver, 2), () -> { });

        assertThat(second.outcome()).isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        // 旧行：UNKNOWN 诚实归档（保留在账，不静默复用不删除）
        RcaToolInvocationLedger.InvocationRecovery orphan = ledger
                .findRecoveryByTask(runId, metricsTask).stream()
                .filter(r -> r.callSeq() == 4 && r.attemptId().equals(attempt1))
                .findFirst().orElseThrow();
        assertThat(orphan.state()).isEqualTo(ToolInvocationState.UNKNOWN);
        assertThat(adminJdbc.sql("SELECT reason_code FROM rca_tool_invocation WHERE id = :id")
                .param("id", orphan.operationId()).query(String.class).single())
                .isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN.name());
        // 新行：新 call_seq（跨 attempt 单调）+ SUCCESS + result_ref 在值
        List<RcaToolInvocationLedger.InvocationRecovery> metricsRows =
                ledger.findRecoveryByTask(runId, metricsTask);
        assertThat(metricsRows).as("成对面：旧行 UNKNOWN + 新行 SUCCESS 共存").hasSize(2);
        RcaToolInvocationLedger.InvocationRecovery reDriven = metricsRows.stream()
                .filter(r -> r.state() == ToolInvocationState.SUCCESS).findFirst().orElseThrow();
        assertThat(reDriven.callSeq()).as("call_seq 跨 attempt 单调（checkpoint 最大值续起）")
                .isEqualTo(5L);
        assertThat(evidence.findById(reDriven.resultRef())).as("新观察记录在库").isPresent();
        assertThat(evidence.findByRunId(runId)).hasSize(evidenceBefore + 1);
        assertThat(taskState(metricsTask)).isEqualTo(RcaTaskState.DONE);
        // 预算占用保留：悬挂请求的 PROVISIONAL 不退款不免费（对账面 M4-37 输入）
        assertThat(adminJdbc.sql("""
                        SELECT state FROM run_budget_entry
                         WHERE run_id = :run AND task_id = :task AND attempt_id = :attempt
                           AND call_seq = 4 AND budget_kind = 'TOOL_CALL'
                        """).param("run", runId).param("task", metricsTask)
                .param("attempt", attempt1).query(String.class).single())
                .isEqualTo("PROVISIONAL");
    }

    // --------------------------------------------- markResultRef CAS + FK 面

    @Test
    void markResultRefCasPersistsFreezesAfterSucceedAndRejectsGhostRef() {
        UUID incidentId = insertIncident("markref");
        UUID runId = castNativeRun(incidentId, Digest.sha256Of("bundle"));
        RcaTask driver = claimDriver(runId);
        RcaAttempt attempt = startedAttempt(driver, 1);

        UUID op = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(op, runId, driver.id(),
                attempt.id(), 1, MetricsAgent.TOOL_NAME, "1",
                Digest.sha256Of("markref").hex()));

        // FK 面：幽灵证据引用在 DB 约束面被拒（BA-60 同律，不靠 Java 自觉）
        assertThatThrownBy(() -> ledger.markResultRef(op, UUID.randomUUID()))
                .as("result_ref FK：不存在的证据 id 不可挂账")
                .isInstanceOf(DataIntegrityViolationException.class);

        EvidenceEnvelope real = annotated(runId, driver.id(), "prometheus", "真证据");
        evidence.insert(real);
        assertThat(ledger.markResultRef(op, real.evidenceId())).isTrue();
        assertThat(resultRefOf(op)).isEqualTo(real.evidenceId());

        // succeed 后 CAS 封口：终态不可改写（二次挂引用失败，原值不变）
        assertThat(ledger.succeed(op)).isTrue();
        EvidenceEnvelope second = annotated(runId, driver.id(), "logs", "迟到引用");
        evidence.insert(second);
        assertThat(ledger.markResultRef(op, second.evidenceId()))
                .as("CAS 锚 PENDING：SUCCESS 后拒绝改写").isFalse();
        assertThat(resultRefOf(op)).isEqualTo(real.evidenceId());
    }

    // --------------------------------------------- findRecoveryByTask 投影面

    @Test
    void findRecoveryByTaskProjectsCheckpointsInCallSeqOrder() {
        UUID incidentId = insertIncident("projection");
        UUID runId = castNativeRun(incidentId, Digest.sha256Of("bundle"));
        RcaTask driver = claimDriver(runId);
        RcaAttempt attempt = startedAttempt(driver, 1);

        UUID opSuccess = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(opSuccess, runId,
                driver.id(), attempt.id(), 1, MetricsAgent.TOOL_NAME, "1",
                Digest.sha256Of("a1").hex()));
        EvidenceEnvelope ref = annotated(runId, driver.id(), "prometheus", "引用");
        evidence.insert(ref);
        assertThat(ledger.markResultRef(opSuccess, ref.evidenceId())).isTrue();
        assertThat(ledger.succeed(opSuccess)).isTrue();

        UUID opNoRef = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(opNoRef, runId,
                driver.id(), attempt.id(), 2, MetricsAgent.TOOL_NAME, "1",
                Digest.sha256Of("a2").hex()));
        assertThat(ledger.succeed(opNoRef)).isTrue();

        UUID opUnknown = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(opUnknown, runId,
                driver.id(), attempt.id(), 3, MetricsAgent.TOOL_NAME, "1",
                Digest.sha256Of("a3").hex()));
        adminJdbc.sql("UPDATE rca_tool_invocation SET started_at = started_at "
                + "- interval '1 hour' WHERE id = :id").param("id", opUnknown).update();
        assertThat(ledger.reclaimPendingOlderThan(Instant.now().minusSeconds(1_800)))
                .isEqualTo(1);

        List<RcaToolInvocationLedger.InvocationRecovery> rows =
                ledger.findRecoveryByTask(runId, driver.id());
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(
                        RcaToolInvocationLedger.InvocationRecovery::callSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
        assertThat(rows.get(0).resultRef()).isEqualTo(ref.evidenceId());
        assertThat(rows.get(0).attemptId()).isEqualTo(attempt.id());
        assertThat(rows.get(0).actionDigest()).isEqualTo(Digest.sha256Of("a1").hex());
        assertThat(rows.get(1).state()).as("无 result_ref 的 SUCCESS ≠ 阶段③候选")
                .isEqualTo(ToolInvocationState.SUCCESS);
        assertThat(rows.get(1).resultRef()).isNull();
        assertThat(rows.get(2).state()).isEqualTo(ToolInvocationState.UNKNOWN);
        assertThat(rows.get(2).resultRef()).isNull();
        assertThat(rows.get(0).operationId()).isEqualTo(opSuccess);
    }

    // ------------------------------------------------------------------ 种子与组装

    /** 完跑一轮全链（三任务 DONE、3 证据、3 SUCCESS 行带 result_ref、run REPORTING） */
    private UUID fullChainOnce(String tag) {
        Digest bundleDigest = publishNativeBundle();
        UUID incidentId = insertIncident(tag);
        UUID runId = castNativeRun(incidentId, bundleDigest);
        RcaTask driver = claimDriver(runId);
        evidence.insert(annotated(runId, driver.id(), "prometheus", "cpu over 95%"));
        evidence.insert(annotated(runId, driver.id(), "logs", "cpu over 95%"));

        RcaAttempt attempt = startedAttempt(driver, 1);
        RcaTaskExecutor.ExecutionResult result = executor.execute(driver,
                runs.findById(runId).orElseThrow(),
                incidents.findById(incidentId).orElseThrow(), attempt, () -> { });
        assertThat(result.outcome())
                .as("首轮全链执行成功").isEqualTo(RcaTaskExecutor.ExecutionResult.Outcome.SUCCEEDED);
        assertThat(tasks.findByRunId(runId))
                .filteredOn(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DONE));
        assertThat(invocationRowCount(runId)).isEqualTo(3);
        return runId;
    }

    private long invocationRowCount(UUID runId) {
        return adminJdbc.sql("SELECT count(*) FROM rca_tool_invocation WHERE run_id = :run")
                .param("run", runId).query(Long.class).single();
    }

    private UUID dagTaskId(UUID runId, String key) {
        return tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow().id();
    }

    private RcaTaskState taskState(UUID taskId) {
        return tasks.findById(taskId).orElseThrow().state();
    }

    private UUID resultRefOf(UUID operationId) {
        return adminJdbc.sql("SELECT result_ref FROM rca_tool_invocation WHERE id = :id")
                .param("id", operationId).query(UUID.class).single();
    }

    private UUID incidentOf(UUID runId) {
        return runs.findById(runId).orElseThrow().incidentId();
    }

    private Digest publishNativeBundle() {
        Map<String, Object> proposal = Map.of(
                "schema_version", PlanProposal.SCHEMA_VERSION,
                "tasks", List.of(
                        Map.of("key", "investigate-metrics", "type", "metrics@1",
                                "inputs", List.of()),
                        Map.of("key", "investigate-logs", "type", "logs@1",
                                "inputs", List.of()),
                        Map.of("key", "investigate-change", "type", "change@1",
                                "inputs", List.of())),
                "edges", List.of());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", POLICY_VERSION);
        content.put("native", Map.of("proposal", proposal));
        ConfigBundle bundle = ConfigBundle.of(content, "it-operator", Instant.now());
        assertThat(bundles.insert(bundle)).isTrue();
        assertThat(bundles.activate(bundle.bundleDigest(), null, "it-operator",
                Instant.now())).isTrue();
        return bundle.bundleDigest();
    }

    private UUID insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                now, now));
        return id;
    }

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

    private RcaTask claimDriver(UUID runId) {
        Optional<RcaTask> claimed = tasks.claimNext(WORKER, Instant.now(),
                Duration.ofMinutes(5));
        assertThat(claimed).as("NATIVE_INVESTIGATE driver 经通用领取面可领").isPresent();
        return claimed.orElseThrow();
    }

    /** 重驱轮取既有 driver 行（租约仍在手——恢复=同 driver 重驱，不重新领取） */
    private RcaTask driverOf(UUID runId) {
        return tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .findFirst().orElseThrow();
    }

    private RcaAttempt startedAttempt(RcaTask driver, int attemptCount) {
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), driver.id(), attemptCount,
                (int) driver.leaseEpoch(), WORKER, RcaAttemptStatus.STARTED, null, null,
                null, Instant.now(), null, null);
        attempts.insert(attempt);
        return attempt;
    }

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

    private static ToolRegistry replayRegistry() {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(MetricsAgent.toolDefinition(4_000L, 65_536L),
                        invocation -> SUCCESS_WITH_SERIES),
                new ToolRegistry.Registration(LogsAgent.toolDefinition(4_000L, 65_536L),
                        invocation -> SUCCESS_WITH_SERIES),
                new ToolRegistry.Registration(ChangeAgent.toolDefinition(4_000L, 65_536L),
                        invocation -> SUCCESS_WITH_SERIES)));
    }
}
