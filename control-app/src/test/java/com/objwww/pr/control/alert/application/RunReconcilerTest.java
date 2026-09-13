package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR §4/§6 RunReconciler 决策表单测（SR03/04/05/07/09/10/11/12 的 UT 面；
 * 真 PG 竞争面归 PostgresRunReconcilerIT-195）。围栏纪律断言：对账不覆盖正常
 * 收尾/取消，不凭 updated_at 批量过期，旧 Run 无 deadline 只警示。
 */
class RunReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();

    private RunReconciler reconciler(RunReconciler.Mode mode) {
        return new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, mode, java.time.Duration.ofSeconds(30), 50);
    }

    // ------------------------------------------------------------------ 场景基座

    private UUID incidentWith(RcaRun run) {
        UUID incidentId = run.incidentId();
        if (stores.incidents.findById(incidentId).isEmpty()) {
            stores.incidents.insert(new Incident(incidentId, "k-" + incidentId,
                    IncidentStatus.FIRING, run.generation(), NOW, NOW, null,
                    run.investigationHash(), null, 1, 1, 0, run.id(), NOW, NOW, NOW, NOW));
        }
        return incidentId;
    }

    private RcaRun run(RcaRunState state, RunPurpose purpose) {
        return new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 3, RunTrigger.INITIAL,
                state, Digest.sha256Of("inv-" + UUID.randomUUID()), NOW,
                NOW.minusSeconds(3600), NOW.minusSeconds(3500), null, null,
                purpose, "ut", null);
    }

    private void driverTask(UUID runId, RcaTaskState state, Instant leaseUntil) {
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, RcaTask.NATIVE_INVESTIGATE,
                state, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), "w1", leaseUntil, 4, 1, 3, NOW.minusSeconds(3600),
                NOW, 0));
    }

    /** 验证通过的终态调查行（材料预提交面，SR §4.3） */
    private void committedMaterials(UUID runId, UUID attemptId) {
        stores.investigations.insertStartedIfAbsent(InvestigationResult.started(
                attemptId, attemptId, runId, 3, 2, null, NOW.minusSeconds(600)));
        stores.investigations.finishTerminal(new InvestigationResult(
                attemptId, attemptId, runId, 3, 2, ExecutionStatus.SUCCEEDED,
                ValidationStatus.STRUCTURE_VALIDATED, null,
                "{\"schema_version\":\"2\",\"summary\":\"s\"}", "ab/" + UUID.randomUUID(),
                Digest.sha256Of("raw"), null, "test-model", null,
                NOW.minusSeconds(600), NOW.minusSeconds(599)));
    }

    // ------------------------------------------------------------------ SR04：租约过期走既有回收

    @Test
    void sr04_expiredLeaseWaitsForExistingReclaimNotSecondDriverOrExpire() {
        RcaRun run = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        driverTask(run.id(), RcaTaskState.LEASED, NOW.minusSeconds(60)); // 租约已过期

        int decided = reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        // 无 deadline（旧路径不追溯制造）→ 不 EXPIRED；无材料 → 不铸 finalize；
        // 等既有回收职责（RcaWorker.recoverExpired），不另造第二 driver
        assertThat(decided).isEqualTo(1);
        assertThat(stores.tasks.findByRunId(run.id()))
                .noneMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    // ------------------------------------------------------------------ SR07：有效租约+未超期不误终止

    @Test
    void sr07_liveLeaseWithinDeadlineIsWaitNotTerminate() {
        RcaRun run = run(RcaRunState.RUNNING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        stores.runs.fixReconcileDeadlineIfAbsent(run.id(), NOW.plusSeconds(1800));
        driverTask(run.id(), RcaTaskState.LEASED, NOW.plusSeconds(300)); // 心跳中的慢调查

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.runs.findById(run.id()).orElseThrow().finishedAt()).isNull();
        assertThat(stores.tasks.findByRunId(run.id()))
                .extracting(RcaTask::state)
                .containsOnly(RcaTaskState.LEASED);
        // 心跳不是业务进展：run 行 untouched（无事件无状态写）
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    // ------------------------------------------------------------------ SR05：孤立 REPORTING 识别与恢复路由

    @Test
    void sr05_reportingOrphanWithMaterialsCastsUniqueFinalizeInSafeRecover() {
        RcaRun run = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        incidentWith(run);
        driverTask(run.id(), RcaTaskState.DEAD, null); // driver 已终态、run 仍活跃=孤立
        committedMaterials(run.id(), UUID.randomUUID());

        // ALERT_ONLY：只输出决策，不动状态
        reconciler(RunReconciler.Mode.ALERT_ONLY).scanOnce();
        assertThat(stores.tasks.findByRunId(run.id()))
                .noneMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));

        // SAFE_RECOVER：铸唯一 finalize 恢复 task + 恢复计数 + 审计事件
        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce();
        List<RcaTask> finalize = stores.tasks.findByRunId(run.id()).stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey())).toList();
        assertThat(finalize).hasSize(1);
        assertThat(finalize.get(0).state()).isEqualTo(RcaTaskState.READY);
        assertThat(stores.runs.recoveryAttemptsOf(run.id())).isEqualTo(1);
        assertThat(stores.rcaEvents.all()).anySatisfy(e -> {
            assertThat(e.runId()).isEqualTo(run.id());
            assertThat(e.eventType()).isEqualTo("RUN_RECOVERY_CAST");
        });

        // SR10：重复扫描不重复铸（uq + 在场检查），恢复次数不虚涨
        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce();
        assertThat(stores.tasks.findByRunId(run.id()).stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey())).count()).isEqualTo(1);
        assertThat(stores.runs.recoveryAttemptsOf(run.id())).isEqualTo(1);
    }

    @Test
    void sr05_reportingOrphanWithoutMaterialsAlertsManualNoAutoAction() {
        RcaRun run = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        driverTask(run.id(), RcaTaskState.DEAD, null); // 无任何终态材料

        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce();

        // 材料不完整 → 不猜结果不自动恢复（人工接管），状态原样
        assertThat(stores.tasks.findByRunId(run.id()))
                .noneMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
    }

    @Test
    void sr05_activeRunWithNoTasksAtAllIsStructuralAlert() {
        RcaRun run = run(RcaRunState.QUEUED, RunPurpose.PRODUCTION);
        stores.runs.insert(run); // 铸 run 后铸 task 前崩溃的形状：零任务

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        // 无 deadline 不 EXPIRED；结构不一致只告警（人工），不猜恢复
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.QUEUED);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    // ------------------------------------------------------------------ SR03：LEGACY_UNKNOWN 不豁免不自动产报告

    @Test
    void sr03_legacyUnknownNeverAutoFinalizedOrExemptedByShape() {
        // 历史影子形（REPORTING+无 driver+DAG 全 DONE）但身份不可证明（LEGACY_UNKNOWN）
        RcaRun legacy = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 2,
                RunTrigger.RERUN, RcaRunState.REPORTING, Digest.sha256Of("legacy"),
                NOW.minus(java.time.Duration.ofDays(3)), NOW.minus(DurationDays(3)),
                NOW.minus(DurationDays(3)), null, null);
        stores.runs.insert(legacy);
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), legacy.id(),
                Am4ShadowTrigger.TASK_METRICS, RcaTaskState.DONE, 5,
                NOW.minus(DurationDays(3)), NOW.minus(DurationDays(3)),
                NOW.plusSeconds(3600), null, null, 0, 1, 2, NOW.minus(DurationDays(3)),
                NOW, 0));

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        // 不凭形状当影子豁免，也不自动 finalize 产正式报告（身份闸=PRODUCTION）；
        // 无 deadline（旧列空）→ 不追溯 EXPIRED——生产缺口仍被分类（结构不一致告警）
        assertThat(stores.runs.findById(legacy.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
        assertThat(stores.tasks.findByRunId(legacy.id()))
                .noneMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));
    }

    private static java.time.Duration DurationDays(long d) {
        return java.time.Duration.ofDays(d);
    }

    // ------------------------------------------------------------------ SR11：QUEUED 超期=FAILED+QUEUE_DEADLINE；无 deadline 只警示

    @Test
    void sr11_queuedPastHardDeadlineFailsWithQueueDeadlineKindInAutoExpire() {
        RcaRun queued = run(RcaRunState.QUEUED, RunPurpose.PRODUCTION);
        stores.runs.insert(queued);
        UUID incidentId = incidentWith(queued);
        stores.runs.fixReconcileDeadlineIfAbsent(queued.id(), NOW.minusSeconds(60));
        driverTask(queued.id(), RcaTaskState.READY, null);

        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce(); // SAFE_RECOVER 不自动过期
        assertThat(stores.runs.findById(queued.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.QUEUED);

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        RcaRun after = stores.runs.findById(queued.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(RcaRunState.FAILED); // QUEUED 无 EXPIRED 出边
        assertThat(after.completionKind()).isEqualTo(RcaRun.COMPLETION_QUEUE_DEADLINE);
        assertThat(after.finishedAt()).isEqualTo(NOW);
        assertThat(after.lastError()).isEqualTo("RECONCILE_DEADLINE");
        // 后续执行资格撤销 + 审计事件 + 恢复计数
        assertThat(stores.tasks.findByRunId(queued.id()))
                .extracting(RcaTask::state).containsOnly(RcaTaskState.CANCELLED);
        assertThat(stores.rcaEvents.all()).anySatisfy(e -> {
            assertThat(e.runId()).isEqualTo(queued.id());
            assertThat(e.eventType()).isEqualTo("RUN_EXPIRED");
        });
        // incident 当前 Run 指针：仍指向本 run → 清理（不误清他 run 指针）
        assertThat(stores.incidents.findById(incidentId).orElseThrow().currentRcaRunId())
                .isNull();
        assertThat(stores.runs.recoveryAttemptsOf(queued.id())).isEqualTo(1);
    }

    @Test
    void sr11_reportingPastHardDeadlineExpiresButLegacyWithoutDeadlineStays() {
        RcaRun reporting = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(reporting);
        stores.runs.fixReconcileDeadlineIfAbsent(reporting.id(), NOW.minusSeconds(1));
        driverTask(reporting.id(), RcaTaskState.LEASED, NOW.plusSeconds(300)); // 心跳中的执行者

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();
        RcaRun expired = stores.runs.findById(reporting.id()).orElseThrow();
        assertThat(expired.state()).isEqualTo(RcaRunState.EXPIRED);
        assertThat(expired.completionKind()).isEqualTo(RcaRun.COMPLETION_DEADLINE_EXPIRED);

        // 旧 Run 无 deadline：即便 AUTO_EXPIRE 也不凭 updated_at 过期（SR11 后半）
        RcaRun legacy = run(RcaRunState.RUNNING, RunPurpose.LEGACY_UNKNOWN);
        legacy = new RcaRun(legacy.id(), legacy.incidentId(), legacy.generation(),
                legacy.trigger(), RcaRunState.RUNNING, legacy.investigationHash(),
                NOW.minus(DurationDays(9)), NOW.minus(DurationDays(9)), null, null, null,
                null, null, null);
        stores.runs.insert(legacy);
        driverTask(legacy.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();
        assertThat(stores.runs.findById(legacy.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.runs.findById(legacy.id()).orElseThrow().finishedAt()).isNull();
    }

    // ------------------------------------------------------------------ SR08/SR09 UT 面：过期竞争败者零副作用

    @Test
    void sr09_expireKeepsPointerOfNewerRunAndLateFinishOnlyAudits() {
        // run A 已被取代链：incident 指针已指向新 run B——过期 A 不得清 B 的指针
        RcaRun stale = run(RcaRunState.RUNNING, RunPurpose.PRODUCTION);
        stores.runs.insert(stale);
        stores.runs.fixReconcileDeadlineIfAbsent(stale.id(), NOW.minusSeconds(1));
        driverTask(stale.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));
        UUID incidentId = incidentWith(stale);
        UUID freshRunId = UUID.randomUUID();
        // 指针已指向新 run（新 run 行不在此构造——断言面只关心指针不被误清）
        stores.incidents.update(new Incident(incidentId, "k-" + incidentId,
                IncidentStatus.FIRING, 3, NOW, NOW, null, stale.investigationHash(), null,
                1, 1, 0, freshRunId, NOW, NOW, NOW, NOW));

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        assertThat(stores.runs.findById(stale.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.EXPIRED);
        assertThat(stores.incidents.findById(incidentId).orElseThrow().currentRcaRunId())
                .as("过期不得误清新 run 的指针").isEqualTo(freshRunId);
    }

    // ------------------------------------------------------------------ SR12：对账自身可恢复（单 run 失败不炸整轮）

    @Test
    void sr12_candidateActionFailureDoesNotKillScan() {
        RcaRun run = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        driverTask(run.id(), RcaTaskState.DEAD, null);
        committedMaterials(run.id(), UUID.randomUUID());

        // 毒化事件面：finalize 铸造路径 events.append 抛错 → per-candidate 兜底捕获，
        // scanOnce 不抛（真 PG 下事务回滚；本断言只认扫描韧性，不认 fake 半写状态）
        com.objwww.pr.control.alert.domain.event.RcaEventAppender poisonEvents =
                new com.objwww.pr.control.alert.domain.event.RcaEventAppender() {
                    @Override
                    public long append(UUID runId,
                            com.objwww.pr.control.alert.domain.event.RcaEventAppender.EventDraft d) {
                        throw new IllegalStateException("event sink down");
                    }

                    @Override
                    public long appendIndependent(UUID runId,
                            com.objwww.pr.control.alert.domain.event.RcaEventAppender.EventDraft d) {
                        throw new IllegalStateException("event sink down");
                    }
                };
        RunReconciler poisoned = new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, poisonEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, RunReconciler.Mode.SAFE_RECOVER, java.time.Duration.ofSeconds(30), 50);

        int decided = poisoned.scanOnce();
        assertThat(decided).isZero(); // 失败候选不计成功，异常未外溢
    }

    // ------------------------------------------------------------------ 模式回归：ALERT_ONLY 零写

    @Test
    void alertOnlyModeWritesNothing() {
        RcaRun run = run(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        stores.runs.insert(run);
        incidentWith(run);
        driverTask(run.id(), RcaTaskState.DEAD, null);
        committedMaterials(run.id(), UUID.randomUUID());
        stores.runs.fixReconcileDeadlineIfAbsent(run.id(), NOW.minusSeconds(1));

        reconciler(RunReconciler.Mode.ALERT_ONLY).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
        assertThat(stores.runs.findById(run.id()).orElseThrow().finishedAt()).isNull();
        assertThat(stores.rcaEvents.all()).isEmpty();
        assertThat(stores.runs.recoveryAttemptsOf(run.id())).isZero();
        assertThat(stores.tasks.findByRunId(run.id()))
                .noneMatch(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()));
    }

    // ------------------------------------------------------------------ WC-5 观测面

    private RunReconciler reconciler(RunReconciler.Mode mode,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics metrics) {
        return new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, mode, java.time.Duration.ofSeconds(30), 50, metrics);
    }

    @Test
    void wc5ReconcileMetricsAdvanceOnScan() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.objwww.pr.control.infrastructure.observability.AlertMetrics metrics =
                new com.objwww.pr.control.infrastructure.observability.AlertMetrics(registry);

        // 活跃 run（活租约）→ WAIT_ACTIVE_LEASE 决策计数；终态 run 名下未决任务 →
        // 清理通道收敛 CANCELLED + cancel_to_quiesce 观测
        RcaRun live = run(RcaRunState.RUNNING, RunPurpose.PRODUCTION);
        stores.runs.insert(live);
        stores.runs.fixReconcileDeadlineIfAbsent(live.id(), NOW.plusSeconds(1800));
        driverTask(live.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));
        RcaRun cancelled = run(RcaRunState.CANCELLED, RunPurpose.PRODUCTION);
        stores.runs.insert(cancelled);
        driverTask(cancelled.id(), RcaTaskState.READY, NOW.plusSeconds(300));

        reconciler(RunReconciler.Mode.SAFE_RECOVER, metrics).scanOnce();

        assertThat(registry.counter("rca_reconcile_decision_total",
                "decision", "WAIT_ACTIVE_LEASE").count()).isEqualTo(1.0);
        assertThat(registry.get("rca_reconcile_last_success_epoch_ms").gauge().value())
                .as("两通道全成功 → last_success 推到本拍时钟")
                .isEqualTo(NOW.toEpochMilli());
        assertThat(registry.get("rca_reconcile_terminal_run_open_tasks").gauge().value())
                .as("清理通道已收敛 → 终态名下未决清零")
                .isEqualTo(0.0);
        assertThat(registry.get("rca_cancel_to_quiesce").timer().count())
                .as("取消 run 的未决任务被收敛 → 观测一次收敛时长")
                .isGreaterThanOrEqualTo(1);
        assertThat(registry.get("rca_reconcile_scan_duration").timers())
                .as("active/cleanup 两通道各一条时长序列")
                .hasSize(2);
    }
}
