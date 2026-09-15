package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PA-A1（V111）：RunReconciler LIVE_BUT_STUCK 档决策表单测（真 PG 竞争面归
 * PostgresRunReconcilerIT 后续增量）。判定纪律：心跳活（租约在续）但 STARTED
 * attempt 的有效进展滞后超阈值；REPORT_FINALIZE 不参与；progress NULL 回退
 * startedAt（V111 存量行语义）；灰度同 mode——ALERT_ONLY 只告警不终止。
 */
class RunReconcilerLiveButStuckTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(5);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();

    private RunReconciler reconciler(RunReconciler.Mode mode) {
        return new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, mode, Duration.ofSeconds(30), 50,
                com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP,
                stores.attempts, THRESHOLD);
    }

    private RcaRun runningProductionRun() {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 3, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("inv-" + UUID.randomUUID()), NOW,
                NOW.minusSeconds(3600), NOW.minusSeconds(3500), null, null,
                RunPurpose.PRODUCTION, "ut", null);
        stores.runs.insert(run);
        stores.runs.fixReconcileDeadlineIfAbsent(run.id(), NOW.plusSeconds(1800));
        stores.incidents.insert(new Incident(run.incidentId(), "k-" + run.incidentId(),
                IncidentStatus.FIRING, 3, NOW, NOW, null, run.investigationHash(),
                null, 1, 1, 0, run.id(), NOW, NOW, NOW, NOW));
        return run;
    }

    /** 心跳中的 driver 任务（租约活）+ STARTED attempt（V111 进度列随 insert 初始化） */
    private UUID leasedDriverWithFreshAttempt(RcaRun run) {
        UUID taskId = UUID.randomUUID();
        stores.tasks.insert(new RcaTask(taskId, run.id(), RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), "w1", NOW.plusSeconds(300), 4, 1, 3,
                NOW.minusSeconds(3600), NOW, 0));
        stores.attempts.insert(new RcaAttempt(UUID.randomUUID(), taskId, 1, 4, "w1",
                RcaAttemptStatus.STARTED, null, null, null, NOW.minusSeconds(3600),
                null, null));
        return taskId;
    }

    // ---------------------------------------------------------------- 判定与灰度

    @Test
    void pa1_stuckAttemptAlertOnlyEmitsButDoesNotTerminate() {
        RcaRun run = runningProductionRun();
        leasedDriverWithFreshAttempt(run); // 进度 = startedAt = 1h 前 > 5min 阈值

        int decided = reconciler(RunReconciler.Mode.ALERT_ONLY).scanOnce();

        assertThat(decided).isEqualTo(1);
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    @Test
    void pa1_safeRecoverStillOnlyAlertsTerminationGatedToAutoExpire() {
        // 灰度纪律（与 HARD_DEADLINE_EXPIRED 同线）：SAFE_RECOVER 不终止，只告警
        RcaRun run = runningProductionRun();
        leasedDriverWithFreshAttempt(run);

        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    @Test
    void pa1_autoExpireTerminatesStuckRunAtomically() {
        RcaRun run = runningProductionRun();
        leasedDriverWithFreshAttempt(run);

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        RcaRun after = stores.runs.findById(run.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(RcaRunState.EXPIRED);
        assertThat(after.finishedAt()).isEqualTo(NOW);
        assertThat(after.completionKind()).isEqualTo(RcaRun.COMPLETION_LIVE_BUT_STUCK);
        assertThat(stores.tasks.findByRunId(run.id()))
                .extracting(RcaTask::state)
                .containsOnly(RcaTaskState.CANCELLED);
        assertThat(stores.incidents.findById(run.incidentId()).orElseThrow().currentRcaRunId())
                .isNull();
        assertThat(stores.rcaEvents.all())
                .anyMatch(e -> "RUN_LIVE_BUT_STUCK".equals(e.eventType()));
    }

    @Test
    void pa1_freshMeaningfulProgressStaysWaitActiveLease() {
        RcaRun run = runningProductionRun();
        UUID taskId = leasedDriverWithFreshAttempt(run);
        // 慢调用不是 stuck：检查点刚推进（activity/progress 同为 1 分钟前）
        stores.attempts.setProgress(stores.attempts.findByTaskId(taskId).get(0).id(),
                NOW.minusSeconds(60), NOW.minusSeconds(60));

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    @Test
    void pa1_legacyNullProgressFallsBackToStartedAt() {
        RcaRun run = runningProductionRun();
        UUID taskId = leasedDriverWithFreshAttempt(run);
        // V111 存量行：双列 NULL → 判定回退 startedAt（1h 前，仍超阈值）
        stores.attempts.setProgress(stores.attempts.findByTaskId(taskId).get(0).id(),
                null, null);

        reconciler(RunReconciler.Mode.AUTO_EXPIRE).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().completionKind())
                .isEqualTo(RcaRun.COMPLETION_LIVE_BUT_STUCK);
    }

    @Test
    void pa1_detectionDisabledWithoutProgressWiring() {
        RcaRun run = runningProductionRun();
        leasedDriverWithFreshAttempt(run);
        // 旧 13 参构造（attempts/阈值未装配）= 检测关闭，行为零变化
        RunReconciler legacy = new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, RunReconciler.Mode.AUTO_EXPIRE, Duration.ofSeconds(30), 50);

        legacy.scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    @Test
    void pa1_leaseLostFallsBackToExistingReclaimNotStuckPath() {
        RcaRun run = runningProductionRun();
        // 心跳也停了（租约已过期）→ LOST 语义归既有 worker 回收档（WAIT_RECLAIM），
        // 不冒充 LIVE_BUT_STUCK（判定前置：租约必须还活着）
        UUID taskId = UUID.randomUUID();
        stores.tasks.insert(new RcaTask(taskId, run.id(), RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), "w1", NOW.minusSeconds(60), 4, 1, 3,
                NOW.minusSeconds(3600), NOW, 0));
        stores.attempts.insert(new RcaAttempt(UUID.randomUUID(), taskId, 1, 4, "w1",
                RcaAttemptStatus.STARTED, null, null, null, NOW.minusSeconds(3600),
                null, null));

        reconciler(RunReconciler.Mode.SAFE_RECOVER).scanOnce();

        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
    }
}
