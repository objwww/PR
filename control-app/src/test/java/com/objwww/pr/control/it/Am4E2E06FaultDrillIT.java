package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunBudgetLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E-M4-06 故障演练 IT（M4-38 随件交付，FAULT_DRILL——技术方案 §15.3：不在 §15.4
 * 强制 195 部署栈清单内，Testcontainers 真 PG 执行）。五个提交边界逐点注入"进程死亡"
 * 并重启——SIGKILL 等价注入 = 组件实例丢弃 + 全新实例同存储重驱；"中断" = 操作只执行
 * 到该边界、后续步骤不执行。逐案断言 §15.3 要求：可重驱、无重复工具副作用、无预算透支、
 * 无重复事件冲突、无永久 BLOCKED；对照案（it06_6）证恢复后最终事实与未杀一致。
 *
 * <p>边界→用例：①plan 落库（it06_1）②tool ledger=PENDING + 预算 RESERVED 悬挂
 * （it06_2）③Claim 落行后重放（it06_3）④finish 事务前中断（it06_4）⑤finish 完成后
 * 重放（it06_5）；对照直通（it06_6）。
 *
 * @author wanghua
 * @date 2026-09-07
 */
class Am4E2E06FaultDrillIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY_VERSION = "e06-policy";
    private static final String WORKER = "e06-worker";
    /** stale 扫描阈值相对当下前移量：DB created_at 晚于 Java 参数时钟差会漏扫刚悬挂的预留 */
    private static final long STALE_AHEAD_SECONDS = 60L;

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        adminJdbc.sql("INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 1), ('rca', 2)")
                .update();
        tasks = new PostgresRcaTaskRepository(controlJdbc);
        runs = new PostgresRcaRunRepository(controlJdbc);
    }

    // ------------------------------------------------------------- 边界① plan 落库后重启

    @Test
    void it06_1_plan落库后进程重启_任务零重建图同构可推进无永久BLOCKED() {
        UUID run = seedRun();
        DeterministicSupervisor first = supervisor();
        assertThat(first.startRun(run, proposal(), Set.of("snapshot:r0")).outcome())
                .isEqualTo(DeterministicSupervisor.StartOutcome.STARTED);
        List<String> graphAfterCrash = graphOf(run);

        // —— SIGKILL 等价：first 实例丢弃，全新实例同存储重驱
        DeterministicSupervisor reborn = supervisor();
        assertThat(reborn.startRun(run, proposal(), Set.of("snapshot:r0")).outcome())
                .as("已有任务图只续驱不重编译")
                .isEqualTo(DeterministicSupervisor.StartOutcome.ALREADY_STARTED);
        assertThat(graphOf(run)).as("任务零重建、图同构").isEqualTo(graphAfterCrash);

        // 重驱后可推进、无永久 BLOCKED：逐层终态 → 全收敛 → REPORTING
        finishAllTasks(reborn, run);
        assertThat(runs.findById(run).orElseThrow().state())
                .as("全任务终态后收敛 REPORTING（无永久 BLOCKED）")
                .isEqualTo(RcaRunState.REPORTING);
    }

    // --------------------------------------- 边界② tool ledger=PENDING + 预算悬挂重启收口

    @Test
    void it06_2_toolPENDING悬挂与预算悬挂_重启收口零重复副作用零透支() {
        UUID run = seedRun();
        supervisor().startRun(run, proposal(), Set.of("snapshot:r0"));
        RcaTask leased = tasks.claimNext(WORKER, Instant.now(),
                Duration.ofMinutes(5)).orElseThrow();
        RcaAttempt attempt = startedAttempt(leased.id());

        RunBudgetLedger firstBudget = budgetLedger();
        firstBudget.ensureLimit(run, BudgetKind.TOOL_CALL, 2);
        ReservationKey key = key(run, leased.id(), attempt.id(), 1, BudgetKind.TOOL_CALL);
        assertThat(firstBudget.reserve(key, 1).allowed()).isTrue();
        RcaToolInvocationLedger.InvocationIdentity identity =
                identity(run, leased.id(), attempt.id(), 1);
        toolLedger().open(identity); // PENDING 先行落档
        // —— SIGKILL 等价：不 settle、不 commit，实例全部丢弃

        // 重启：全新实例同存储收口
        RunBudgetLedger rebornBudget = budgetLedger();
        RcaToolInvocationLedger rebornLedger = toolLedger();
        assertThat(stateOf(identity.operationId()))
                .as("进程死后 PENDING 悬挂可查").isEqualTo("PENDING");
        assertThat(rebornBudget.findStaleReservations(
                Instant.now().plusSeconds(STALE_AHEAD_SECONDS)))
                .as("悬挂预留可被 stale 扫描发现").contains(key);

        // 收口：工具账本 UNKNOWN（传输未知不伪造成功）+ 预算 PROVISIONAL→UNMATCHED
        //（结局未知不冒进不重复计费）
        assertThat(rebornLedger.fail(identity.operationId(), ToolInvocationState.UNKNOWN,
                ToolReasonCode.TRANSPORT_UNKNOWN)).isTrue();
        rebornBudget.provisional(key);
        rebornBudget.markUnmatched(key);
        assertThat(entryState(key)).isEqualTo("UNMATCHED");
        assertThat(consumedOf(run, BudgetKind.TOOL_CALL))
                .as("无透支：收口后账面不超限").isLessThanOrEqualTo(2L);

        // 零重复工具副作用：同逻辑调用重放被逻辑幂等键显式拒绝，账面仍一行
        assertThatThrownBy(() -> rebornLedger.open(identity))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("rca_tool_invocation")).isEqualTo(1);
    }

    // ------------------------------------------- 边界③ Claim 落行后重放 append

    @Test
    void it06_3_claim落行后进程死重放append_零重复行零重复事件() {
        UUID run = seedRun();
        ClaimStore first = claimStore();
        ClaimVerdict verdict = verdict();

        assertThat(first.append(run, verdict).outcome())
                .isEqualTo(ClaimProjection.Outcome.CREATED);
        long createdEvents = eventCount("CLAIM_CREATED");

        // —— SIGKILL 等价：append 后进程死，重启重放同一 verdict（事件 ID 幂等键：
        // 重放不得产生第二行/第二事件）
        ClaimStore reborn = claimStore();
        assertThat(reborn.append(run, verdict).outcome())
                .isEqualTo(ClaimProjection.Outcome.UNCHANGED);
        assertThat(reborn.findByRunId(run)).as("零重复行").hasSize(1);
        assertThat(eventCount("CLAIM_CREATED")).as("零重复事件").isEqualTo(createdEvents);
    }

    // --------------------------------------------- 边界④ finish 事务前中断重启

    @Test
    void it06_4_finish前中断重启_恰一份报告_重启侧预算实扣收口() {
        UUID run = seedRun();
        supervisor().startRun(run, proposal(), Set.of("snapshot:r0"));
        RcaTask leased = tasks.claimNext(WORKER, Instant.now(),
                Duration.ofMinutes(5)).orElseThrow();
        RcaAttempt attempt = startedAttempt(leased.id());
        RunBudgetLedger budget = budgetLedger();
        budget.ensureLimit(run, BudgetKind.STEP, 8);
        ReservationKey stepKey = key(run, leased.id(), attempt.id(), 1, BudgetKind.STEP);
        budget.reserve(stepKey, 1);
        // —— SIGKILL 等价：finishTask 前进程死（attempt 停 STARTED、预算停 RESERVED）

        // 重启：全新 orchestrator 以同一在期租约收尾；重启侧按事实显式结算实扣
        //（orchestrator 不感知 RunBudgetLedger，预算结算是执行侧职责）
        RcaRunOrchestrator reborn = orchestrator();
        assertThat(reborn.finishTask(tasks.findById(leased.id()).orElseThrow(), WORKER,
                -1, -1, RcaTaskExecutor.ExecutionResult.success(artifact()), attempt))
                .isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
        budget.commit(stepKey, 1);
        assertThat(entryState(stepKey)).isEqualTo("COMMITTED");
        assertThat(consumedOf(run, BudgetKind.STEP)).isEqualTo(1L);
        assertThat(count("rca_report")).as("恰一份报告").isEqualTo(1);
        assertThat(tasks.findById(leased.id()).orElseThrow().state())
                .isEqualTo(RcaTaskState.DONE);
    }

    // --------------------------------------------- 边界⑤ finish 完成后重放

    @Test
    void it06_5_finish完成后重放_终态不可改写零重复报告() {
        UUID run = seedRun();
        supervisor().startRun(run, proposal(), Set.of("snapshot:r0"));
        RcaTask leased = tasks.claimNext(WORKER, Instant.now(),
                Duration.ofMinutes(5)).orElseThrow();
        RcaAttempt attempt = startedAttempt(leased.id());
        RcaRunOrchestrator first = orchestrator();
        assertThat(first.finishTask(tasks.findById(leased.id()).orElseThrow(), WORKER,
                -1, -1, RcaTaskExecutor.ExecutionResult.success(artifact()), attempt))
                .isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
        long reportsBefore = count("rca_report");

        // —— SIGKILL 等价：finish 后进程死，重启重放同一收尾。run 已 SUCCEEDED 出
        // 活跃集 → 代际栅栏走 STALE 分支 → 终态 DONE 无出边，状态机确定性拒绝（抛 =
        // fail-closed）；报告/publication 落档在栅栏之后，重放零新增
        try {
            orchestrator().finishTask(tasks.findById(leased.id()).orElseThrow(), WORKER,
                    -1, -1, RcaTaskExecutor.ExecutionResult.success(artifact()), attempt);
        } catch (RuntimeException rejected) {
            // DONE→STALE 终态二次迁移被状态机拒绝 = 预期 fail-closed 路径
        }
        assertThat(count("rca_report")).as("零重复报告").isEqualTo(reportsBefore);
        assertThat(tasks.findById(leased.id()).orElseThrow().state())
                .as("任务保持 DONE（终态不可改写）").isEqualTo(RcaTaskState.DONE);
    }

    // --------------------------------------------- 对照案：扰动重驱 vs 直通终态对拍

    @Test
    void it06_6_边界扰动重驱与直通对照_最终事实一致() {
        UUID direct = seedRun();
        DeterministicSupervisor directSupervisor = supervisor();
        directSupervisor.startRun(direct, proposal(), Set.of("snapshot:r0"));
        finishAllTasks(directSupervisor, direct);

        UUID disrupted = seedRun();
        supervisor().startRun(disrupted, proposal(), Set.of("snapshot:r0"));
        // 中途注入一次"进程死亡"（实例丢弃换新实例）再继续推进
        DeterministicSupervisor reborn = supervisor();
        reborn.advance(disrupted);
        finishAllTasks(reborn, disrupted);
        reborn.advance(disrupted);

        assertThat(runFacts(disrupted)).as("恢复后最终事实与未杀对照一致")
                .isEqualTo(runFacts(direct));
        assertThat(runs.findById(disrupted).orElseThrow().state())
                .isEqualTo(runs.findById(direct).orElseThrow().state());
    }

    // ------------------------------------------------------------------ 组件组装

    private DeterministicSupervisor supervisor() {
        return new DeterministicSupervisor(
                new PlanCompiler(agentRegistry(), tasks,
                        new PostgresTaskEdgeRepository(controlJdbc), controlTx),
                new DagExecutionService(new PostgresTaskEdgeRepository(controlJdbc), tasks),
                runs, tasks, controlTx, AlertClock.system());
    }

    private RcaRunOrchestrator orchestrator() {
        return new RcaRunOrchestrator(tasks, runs,
                new PostgresRcaAttemptRepository(controlJdbc),
                new PostgresRcaReportRepository(controlJdbc),
                new PostgresIncidentRepository(controlJdbc),
                new PostgresSchedulerSlotRepository(controlJdbc),
                new PostgresInvestigationResultRepository(controlJdbc),
                new PostgresRcaToolCallRepository(controlJdbc),
                new ReportCompletedNotifier(
                        new PostgresReportPublicationRepository(controlJdbc),
                        new PostgresNotifyOutboxRepository(controlJdbc),
                        List.of("test"), "am3-candidate-v1", 280),
                new AlertInMemoryStores.Cas(), SlaPolicy.defaults(),
                AlertClock.system(), "rca", AlertMetrics.NOOP);
    }

    private AgentRegistry agentRegistry() {
        return new AgentRegistry(List.of(
                agent("metrics"), agent("logs"), agent("reduce")));
    }

    private static AgentProfile agent(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, POLICY_VERSION, Set.of(),
                Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"));
    }

    private RunBudgetLedger budgetLedger() {
        return new PostgresRunBudgetLedger(controlJdbc, controlTx);
    }

    private PostgresRcaToolInvocationLedger toolLedger() {
        return new PostgresRcaToolInvocationLedger(controlJdbc, controlTx);
    }

    private PostgresClaimStore claimStore() {
        PostgresRcaEventAppender events = new PostgresRcaEventAppender(controlJdbc,
                controlTx, new TransactionTemplate(
                        new DataSourceTransactionManager(controlDataSource())));
        return new PostgresClaimStore(controlJdbc, controlTx, MAPPER, events);
    }

    /** 冻结提案（与 DeterministicSupervisorTest 同形）：双调查 REQUIRED 汇入归并 */
    private static Map<String, Object> proposal() {
        List<Map<String, Object>> planTasks = List.of(
                Map.of("key", "investigate-a", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "investigate-b", "type", "logs@1", "inputs", List.of()),
                Map.of("key", "reduce", "type", "reduce@1",
                        "inputs", List.of("snapshot:r0")));
        List<Map<String, Object>> planEdges = List.of(
                Map.of("from", "investigate-a", "to", "reduce", "dependency", "REQUIRED"),
                Map.of("from", "investigate-b", "to", "reduce", "dependency", "REQUIRED"));
        return Map.of("schema_version", "am4-plan.v1",
                "tasks", planTasks, "edges", planEdges);
    }

    // ------------------------------------------------------------------ 夹具与断言

    private UUID seedRun() {
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=FaultDrill|service=e06-" + incident).update();
        UUID run = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", run).param("inc", incident)
                .param("hash", Digest.sha256Of("e06-" + run).value()).update();
        return run;
    }

    /** 任务图指纹（key/state 按 key 排序拼接）——图同构断言用 */
    private List<String> graphOf(UUID run) {
        return tasks.findByRunId(run).stream()
                .map(t -> t.taskKey() + "/" + t.state().name())
                .sorted()
                .toList();
    }

    /** 逐任务终态化（模拟 worker 完成事实已落）：claimNext 真实领取 → DONE 直置 →
     * advance 放行后继（BLOCKED→READY）；无 READY 可领即收敛完成 */
    private void finishAllTasks(DeterministicSupervisor supervisor, UUID run) {
        while (true) {
            var leased = tasks.claimNext(WORKER, Instant.now(), Duration.ofMinutes(5));
            if (leased.isEmpty()) {
                return;
            }
            controlJdbc.sql("UPDATE rca_task SET state = 'DONE', updated_at = now() "
                            + "WHERE id = :id")
                    .param("id", leased.orElseThrow().id()).update();
            supervisor.advance(run);
        }
    }

    /** 终局事实对拍面：任务 key/state 串 + 边 key 对串（UUID 归一为 task_key） */
    private List<String> runFacts(UUID run) {
        String taskStates = controlJdbc.sql("""
                SELECT coalesce(string_agg(task_key || '/' || state, ',' ORDER BY task_key), '')
                  FROM rca_task WHERE run_id = :run
                """).param("run", run).query(String.class).single();
        String edgeKeys = controlJdbc.sql("""
                SELECT coalesce(string_agg(ft.task_key || '>' || tt.task_key, ','
                    ORDER BY ft.task_key, tt.task_key), '')
                  FROM rca_task_edge e
                  JOIN rca_task ft ON ft.id = e.from_task_id
                  JOIN rca_task tt ON tt.id = e.to_task_id
                 WHERE e.run_id = :run
                """).param("run", run).query(String.class).single();
        return List.of(taskStates, edgeKeys);
    }

    private RcaAttempt startedAttempt(UUID taskId) {
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), taskId, 1, 1, WORKER,
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null);
        new PostgresRcaAttemptRepository(controlJdbc).insert(attempt);
        return attempt;
    }

    private static ReservationKey key(UUID run, UUID task, UUID attempt, int seq,
            BudgetKind kind) {
        return new ReservationKey(run, task, attempt, seq, kind);
    }

    private static RcaToolInvocationLedger.InvocationIdentity identity(UUID run, UUID task,
            UUID attempt, int seq) {
        return new RcaToolInvocationLedger.InvocationIdentity(UUID.randomUUID(), run,
                task, attempt, seq, "prometheus.query", "1",
                Digest.sha256Of("e06-" + run + "-" + seq).value());
    }

    private String stateOf(UUID operationId) {
        return controlJdbc.sql("SELECT state FROM rca_tool_invocation WHERE id = :id")
                .param("id", operationId).query(String.class).single();
    }

    private String entryState(ReservationKey key) {
        return controlJdbc.sql("""
                SELECT state FROM run_budget_entry
                 WHERE run_id = :run AND task_id = :task AND attempt_id = :attempt
                   AND call_seq = :seq AND budget_kind = :kind
                """).param("run", key.runId()).param("task", key.taskId())
                .param("attempt", key.attemptId()).param("seq", key.callSeq())
                .param("kind", key.budgetKind().name())
                .query(String.class).single();
    }

    private long consumedOf(UUID run, BudgetKind kind) {
        return controlJdbc.sql("""
                SELECT consumed_units FROM run_budget_state
                 WHERE run_id = :run AND budget_kind = :kind
                """).param("run", run).param("kind", kind.name())
                .query(Long.class).single();
    }

    private ClaimVerdict verdict() {
        return new ClaimVerdict("latency-high", "scope", "2026-09-05/1h", 0, null,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("metrics-agent"),
                "e06 根因断言", List.of("ev-1"), POLICY_VERSION);
    }

    private long eventCount(String eventType) {
        return controlJdbc.sql("SELECT count(*) FROM rca_event WHERE event_type = :type")
                .param("type", eventType).query(Long.class).single();
    }

    private static RcaTaskExecutor.AttemptArtifact artifact() {
        return new RcaTaskExecutor.AttemptArtifact(1, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"1\"}", "raw", null, List.of(),
                null, null, "deepseek-v3", null, null, null, true);
    }
}
