package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.application.ReportFinalizeExecutor;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.RunReconciler;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportWinnerRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SR 验收（L1，真 PG）：影子收口释放活跃唯一位 + RunReconciler 对账竞争面
 * （docs/告警-Run停滞对账与影子执行收口方案-v1.md §6）。
 *
 * <p>钉四件事（V25 部分唯一索引 / V108 新列 / CLAIM_SQL 谓词 / 行锁竞争可核）：
 * <ul>
 *   <li>SR02：影子 Run 进入合法非活跃终态后，同 incident 新生产 Run 可入活跃集
 *       （索引不删不弱化）；purpose/completionKind/对账三列真 PG 往返；真
 *       CLAIM_SQL 排除影子任务、放行生产任务；</li>
 *   <li>SR06：双线程并发 scanOnce 只铸一条 REPORT_FINALIZE（run 行锁复核 + uq
 *       双闸）；worker 路径（真 claim → 只组既有材料 → finishTask 单事务）报告/
 *       发布/outbox 恰一份；</li>
 *   <li>SR08：finishTask 与 AUTO_EXPIRE 过期真并发（双线程栅栏）——SUCCEEDED+
 *       已发布 ∨ EXPIRED+零报告，无中间态；</li>
 *   <li>SR09：过期后晚到 finishTask = LEASE_REJECTED 一行不写（只配审计）。</li>
 * </ul>
 */
class PostgresRunReconcilerIT extends PostgresITBase {

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private PostgresRcaAttemptRepository attempts;
    private IncidentRepository incidents;
    private InvestigationResultRepository investigations;
    private RcaReportRepository reports;
    private RcaEventAppender events;
    private RcaRunOrchestrator orchestrator;
    private final AlertInMemoryStores.Cas cas = new AlertInMemoryStores.Cas();

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        attempts = new PostgresRcaAttemptRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        investigations = new PostgresInvestigationResultRepository(jdbc);
        reports = new PostgresRcaReportRepository(jdbc);
        events = new PostgresRcaEventAppender(jdbc, controlTx, requiresNew());
        orchestrator = new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                new PostgresSchedulerSlotRepository(jdbc), investigations,
                new PostgresRcaToolCallRepository(jdbc),
                new ReportCompletedNotifier(new PostgresReportPublicationRepository(jdbc),
                        new PostgresNotifyOutboxRepository(jdbc), List.of("test"),
                        "sr-it", 280),
                cas, SlaPolicy.defaults(), AlertClock.system(), "rca", AlertMetrics.NOOP,
                CanaryRouter.holmesOnly(), new PostgresReportWinnerRepository(jdbc), null);
    }

    private RunReconciler reconciler(RunReconciler.Mode mode) {
        return new RunReconciler(runs, tasks, reports, investigations, incidents, events,
                controlTx, SlaPolicy.defaults(), AlertClock.system(), mode,
                Duration.ofSeconds(30), 50);
    }

    // ------------------------------------------------ SR02：影子收口释放活跃唯一位

    @Test
    @DisplayName("SR02 影子终态释放 V25 活跃位：新生产 Run 可入；purpose/completionKind 往返；claim 排除影子")
    void sr02_shadowClosureReleasesActiveUniqueSlot() {
        UUID incidentId = seedIncident("sr02");
        RcaRun shadow = insertRun(incidentId, RcaRunState.RUNNING, RunPurpose.SHADOW);
        insertTask(shadow.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.READY);

        // 影子活跃期间：同 incident 第二活跃 Run 被 V25 部分唯一索引拒绝（索引不弱化）
        assertThatThrownBy(() -> insertRun(incidentId, RcaRunState.QUEUED, RunPurpose.PRODUCTION))
                .isInstanceOf(DuplicateKeyException.class);

        // 影子活跃期间其任务不可被生产调度领取（真 CLAIM_SQL coalesce 谓词）
        assertThat(tasks.claimNext("worker-a", Instant.now(), Duration.ofMinutes(5))).isEmpty();

        // 影子取证完成 → 合法非活跃终态 + completionKind 往返（真 PG 列）
        runs.update(closed(shadow, RcaRunState.SUCCEEDED,
                RcaRun.COMPLETION_SHADOW_EVIDENCE_ONLY));
        RcaRun closedShadow = runs.findById(shadow.id()).orElseThrow();
        assertThat(closedShadow.state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(closedShadow.purpose()).isEqualTo(RunPurpose.SHADOW);
        assertThat(closedShadow.completionKind())
                .isEqualTo(RcaRun.COMPLETION_SHADOW_EVIDENCE_ONLY);

        // 活跃位释放：新生产 Run 可入（两历史 Run 语义在真 PG 同样成立）
        RcaRun production = insertRun(incidentId, RcaRunState.QUEUED, RunPurpose.PRODUCTION);
        insertTask(production.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.READY);
        assertThat(runs.findActiveByIncidentId(incidentId).orElseThrow().id())
                .isEqualTo(production.id());

        // 生产任务可领取；对账候选只含生产 Run（影子已出活跃集）
        assertThat(tasks.claimNext("worker-a", Instant.now(), Duration.ofMinutes(5)))
                .hasValueSatisfying(t -> assertThat(t.runId()).isEqualTo(production.id()));
        List<RcaRunRepository.ReconcileCandidate> candidates = runs.findActiveForReconcile(50);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).id()).isEqualTo(production.id());
        assertThat(candidates.get(0).purpose()).isEqualTo(RunPurpose.PRODUCTION);

        // 对账三列真 PG 往返（deadline 冻结不覆盖 / reporting 记时不覆盖 / 恢复计数累加）；
        // timestamptz 微秒精度——种子先行截断，往返断言不比纳秒尾差
        Instant deadline = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .plusSeconds(1800);
        runs.fixReconcileDeadlineIfAbsent(production.id(), deadline);
        runs.fixReconcileDeadlineIfAbsent(production.id(), deadline.plusSeconds(999));
        runs.markReportingStarted(production.id(), Instant.now());
        runs.incrementRecoveryAttempts(production.id());
        runs.incrementRecoveryAttempts(production.id());
        RcaRunRepository.ReconcileCandidate after = runs.findActiveForReconcile(50).get(0);
        assertThat(after.reconcileDeadlineAt()).isEqualTo(deadline);   // 先冻结者胜
        assertThat(after.reportingStartedAt()).isNotNull();
        assertThat(after.recoveryAttempts()).isEqualTo(2);
    }

    // ------------------------------------------------ SR06：并发对账只铸一条 + 恢复发布恰一次

    @Test
    @DisplayName("SR06 双线程 scanOnce 恰一 finalize；真 claim→只组材料→finishTask 发布恰一份")
    void sr06_concurrentReconcileCastsSingleFinalizeThenPublishesExactlyOnce() throws Exception {
        UUID incidentId = seedIncident("sr06");
        RcaRun run = insertRun(incidentId, RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        runs.fixReconcileDeadlineIfAbsent(run.id(), Instant.now().plusSeconds(3600));
        RcaTask driver = insertTask(run.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.DEAD);
        commitMaterials(run.id(), driver);

        // 双线程并发对账（SAFE_RECOVER）：run 行锁复核 + uq(run,round,task_key) 双闸收敛
        RunReconciler a = reconciler(RunReconciler.Mode.SAFE_RECOVER);
        RunReconciler b = reconciler(RunReconciler.Mode.SAFE_RECOVER);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger decided = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread ta = Thread.ofPlatform().name("reconcile-a").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                decided.addAndGet(a.scanOnce());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread tb = Thread.ofPlatform().name("reconcile-b").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                decided.addAndGet(b.scanOnce());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        ta.start();
        tb.start();
        ta.join(30_000);
        tb.join(30_000);
        assertThat(failure.get()).as("并发对账零异常").isNull();

        assertThat(finalizeCount(run.id())).as("并发只铸一条 REPORT_FINALIZE").isEqualTo(1);
        assertThat(recoveryAttemptsOf(run.id())).isEqualTo(1);
        assertThat(eventCount(run.id(), "RUN_RECOVERY_CAST")).isEqualTo(1);

        // 再扫不重复（run 仍活跃但 finalize 在场 → WAIT_BACKOFF）
        assertThat(reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce()).isEqualTo(1);
        assertThat(finalizeCount(run.id())).isEqualTo(1);
        assertThat(recoveryAttemptsOf(run.id())).isEqualTo(1);

        // worker 路径：真 CLAIM_SQL 领取 finalize（REPORT_FINALIZE 键在生产 run 活跃时可领）
        RcaTask claimed = tasks.claimNext("worker-a", Instant.now(), Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(claimed.taskKey()).isEqualTo(RcaTask.REPORT_FINALIZE);
        RcaRun claimedRun = runs.findByIdForUpdate(claimed.runId()).orElseThrow();
        Incident incident = incidents.findById(claimedRun.incidentId()).orElseThrow();
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), claimed.id(),
                claimed.attemptCount(), claimed.leaseEpoch(), "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null, null);
        attempts.insert(attempt);
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                attempt.id(), attempt.id(), claimedRun.id(), claimedRun.generation(), 2,
                null, Instant.now()));

        // 只组既有持久材料（无 LLM 无重查现场）→ finishTask 单事务发布链
        ReportFinalizeExecutor finalizer = new ReportFinalizeExecutor(reports,
                investigations, cas);
        RcaTaskExecutor.ExecutionResult result = finalizer.execute(claimed, claimedRun,
                incident, attempt, () -> { });
        controlTx.executeWithoutResult(status -> orchestrator.finishTask(
                claimed, "worker-a", -1, -1, result, attempt));

        assertThat(count("rca_report")).as("报告恰一份").isEqualTo(1);
        assertThat(count("report_publication")).as("发布恰一份").isEqualTo(1);
        assertThat(count("notify_outbox")).as("outbox 恰一渠道").isEqualTo(1);
        assertThat(count("report_generation_winner")).isEqualTo(1);
        assertThat(runs.findById(run.id()).orElseThrow().state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(tasks.findById(claimed.id()).orElseThrow().state())
                .isEqualTo(RcaTaskState.DONE);

        // 已终态：后续对账零候选零动作（幂等收敛）
        assertThat(reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce()).isZero();
        assertThat(count("rca_report")).isEqualTo(1);
    }

    // ------------------------------------------------ SR08：finishTask vs AUTO_EXPIRE 真并发

    @Test
    @DisplayName("SR08 竞态一致性：收尾与过期双线程并发 → SUCCEEDED+已发布 ∨ EXPIRED+零报告，无中间态")
    void sr08_finishTaskVsAutoExpireRaceIsConsistent() throws Exception {
        UUID incidentId = seedIncident("sr08");
        RcaRun run = insertRun(incidentId, RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        runs.fixReconcileDeadlineIfAbsent(run.id(), Instant.now().minusSeconds(60)); // 硬期限已过
        RcaTask task = insertTask(run.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.LEASED);
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), task.id(), 1, 0, "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null, null);
        attempts.insert(attempt);
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                attempt.id(), attempt.id(), run.id(), run.generation(), 2, null,
                Instant.now()));
        RcaTask fresh = tasks.findById(task.id()).orElseThrow();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> finishOutcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread finisher = Thread.ofPlatform().name("sr08-finisher").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                controlTx.executeWithoutResult(status -> finishOutcome.set(
                        orchestrator.finishTask(fresh, "worker-a", -1, -1,
                                RcaTaskExecutor.ExecutionResult.success(validatedArtifact()),
                                attempt)));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread expirer = Thread.ofPlatform().name("sr08-expirer").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        finisher.start();
        expirer.start();
        finisher.join(30_000);
        expirer.join(30_000);
        assertThat(failure.get()).as("竞态双方零异常").isNull();

        RcaRun after = runs.findById(run.id()).orElseThrow();
        if (after.state() == RcaRunState.SUCCEEDED) {
            // 收尾先胜：过期方锁下复核已出活跃集 → 零写；发布链完整恰一份
            assertThat(finishOutcome.get())
                    .isEqualTo(RcaRunOrchestrator.FinishOutcome.COMPLETED);
            assertThat(count("rca_report")).isEqualTo(1);
            assertThat(count("report_publication")).isEqualTo(1);
            assertThat(after.completionKind()).isNull();
            assertThat(tasks.findById(task.id()).orElseThrow().state())
                    .isEqualTo(RcaTaskState.DONE);
        } else if (after.state() == RcaRunState.EXPIRED) {
            // 过期先胜：状态机终止 + 资格撤销；晚到收尾被租约栅栏拒绝（零报告）
            assertThat(after.completionKind()).isEqualTo(RcaRun.COMPLETION_DEADLINE_EXPIRED);
            assertThat(count("rca_report")).as("过期后零正式报告").isZero();
            assertThat(count("report_publication")).isZero();
            assertThat(tasks.findById(task.id()).orElseThrow().state())
                    .isEqualTo(RcaTaskState.CANCELLED);
            assertThat(finishOutcome.get()).isIn(RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED,
                    RcaRunOrchestrator.FinishOutcome.STALE_GENERATION);
            assertThat(incidents.findById(incidentId).orElseThrow().currentRcaRunId())
                    .as("过期清指针（指针仍指本 run）").isNull();
        } else {
            throw new AssertionError("竞态终态不在一致集内: " + after.state());
        }
    }

    // ------------------------------------------------ SR09：过期后晚到收尾只审计

    @Test
    @DisplayName("SR09 过期落地后晚到 finishTask：LEASE_REJECTED 一行不写（对账不覆盖已定结局）")
    void sr09_lateFinishAfterExpireIsRejectedWithZeroWrites() {
        UUID incidentId = seedIncident("sr09");
        RcaRun run = insertRun(incidentId, RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        runs.fixReconcileDeadlineIfAbsent(run.id(), Instant.now().minusSeconds(60));
        RcaTask task = insertTask(run.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.LEASED);
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), task.id(), 1, 0, "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null, null);
        attempts.insert(attempt);
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                attempt.id(), attempt.id(), run.id(), run.generation(), 2, null,
                Instant.now()));
        RcaTask snapshot = tasks.findById(task.id()).orElseThrow();

        assertThat(reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce()).isEqualTo(1);
        assertThat(runs.findById(run.id()).orElseThrow().state()).isEqualTo(RcaRunState.EXPIRED);

        // 晚到的成功结果：租约栅栏（任务已 CANCELLED）拒绝，一行不写
        RcaRunOrchestrator.FinishOutcome outcome = controlTx.execute(status ->
                orchestrator.finishTask(snapshot, "worker-a", -1, -1,
                        RcaTaskExecutor.ExecutionResult.success(validatedArtifact()), attempt));
        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED);
        assertThat(count("rca_report")).as("晚到结果零报告").isZero();
        assertThat(count("rca_investigation_result"))
                .as("晚到结果不落终态（STARTED 行留给悬挂对账标 UNKNOWN）").isEqualTo(1);
        assertThat(runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.EXPIRED);   // 不复活不覆盖
    }

    // ------------------------------------------------------------------ 种子与助手

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private UUID seedIncident(String tag) {
        UUID incidentId = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        return incidentId;
    }

    private RcaRun insertRun(UUID incidentId, RcaRunState state, RunPurpose purpose) {
        Instant now = Instant.now();
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("run-" + UUID.randomUUID()), now.minus(Duration.ofMinutes(4)),
                now, state == RcaRunState.QUEUED ? null : now, null, null,
                purpose, "sr-it", null);
        runs.insert(run);
        return run;
    }

    private static RcaRun closed(RcaRun run, RcaRunState state, String completionKind) {
        return new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(), state,
                run.investigationHash(), run.createdAt(), Instant.now(), run.startedAt(),
                Instant.now(), null, run.purpose(), run.purposeSource(), completionKind);
    }

    private RcaTask insertTask(UUID runId, String taskKey, RcaTaskState state) {
        Instant now = Instant.now();
        RcaTask task = new RcaTask(UUID.randomUUID(), runId, taskKey, state, 3,
                now.minus(Duration.ofMinutes(4)), now.minus(Duration.ofMinutes(4)),
                now.plusSeconds(3600), state == RcaTaskState.LEASED ? "worker-a" : null,
                state == RcaTaskState.LEASED ? now.plusSeconds(300) : null,
                0, 1, 3, now.minus(Duration.ofMinutes(4)), now, 0);
        tasks.insert(task);
        return task;
    }

    /** 材料预提交面镜像：崩溃 worker 的终态 attempt + CAS raw + 验证通过终态调查行
     *  （rca_investigation_result.attempt_id 有 FK——attempt 行必须先在） */
    private void commitMaterials(UUID runId, RcaTask driverTask) {
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.now();
        attempts.insert(new RcaAttempt(attemptId, driverTask.id(), 1, 0, "crashed-worker",
                RcaAttemptStatus.SUCCEEDED, null, null, null,
                now.minusSeconds(700), now.minusSeconds(599), null));
        String raw = "raw-sr06-" + UUID.randomUUID();
        Digest rawDigest = Digest.sha256Of(raw);
        String ref = cas.putIfAbsent(rawDigest, raw.getBytes(StandardCharsets.UTF_8));
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                attemptId, attemptId, runId, 0, 2, null, now.minusSeconds(600)));
        investigations.finishTerminal(new InvestigationResult(
                attemptId, attemptId, runId, 0, 2, ExecutionStatus.SUCCEEDED,
                ValidationStatus.STRUCTURE_VALIDATED, null,
                "{\"schema_version\":\"2\",\"summary\":\"disk full on node-3\","
                        + "\"root_cause\":{\"component\":\"disk\",\"fault_type\":\"RESOURCE\","
                        + "\"reason_code\":\"DISK_FULL\"},\"impact\":\"写失败\","
                        + "\"remediation\":\"扩容\"}",
                ref, rawDigest, null, "test-model",
                "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}",
                now.minusSeconds(600), now.minusSeconds(599)));
    }

    private static RcaTaskExecutor.AttemptArtifact validatedArtifact() {
        return new RcaTaskExecutor.AttemptArtifact(2, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"2\",\"summary\":\"s\"}", "raw", null,
                List.of(), Digest.sha256Of("raw"), null, "deepseek-v3",
                null, null, null, true, null);
    }

    private long finalizeCount(UUID runId) {
        return adminJdbc.sql(
                        "SELECT count(*) FROM rca_task WHERE run_id=:r AND task_key='REPORT_FINALIZE'")
                .param("r", runId).query(Long.class).single();
    }

    private int recoveryAttemptsOf(UUID runId) {
        return adminJdbc.sql("SELECT recovery_attempts FROM rca_run WHERE id=:r")
                .param("r", runId).query(Integer.class).single();
    }

    private long eventCount(UUID runId, String eventType) {
        return adminJdbc.sql(
                        "SELECT count(*) FROM rca_event WHERE run_id=:r AND event_type=:t")
                .param("r", runId).param("t", eventType).query(Long.class).single();
    }
}
