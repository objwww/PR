package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WC-4 单测（方案 v2 §6；真 PG 面归 PostgresRunReconcilerFairnessIT-195）：
 * <ul>
 *   <li>T01/T02：keyset 分页公平——批上限不饿死后续条、同 created_at 按 id 稳定翻页、
 *       异常条不阻塞游标、尾部回卷；</li>
 *   <li>T04/T05：finalizer 状态分类（READY/RETRY_WAIT/LEASED→等待或回收；
 *       DEAD/CANCELLED/DONE→RECOVERY_EXHAUSTED / FINALIZER_DONE_RUN_OPEN，
 *       不再无限退避、不换 key 重铸绕预算）；</li>
 *   <li>T22/T27（UT 面）：终态 Run 清理通道——资格撤销 + 指针条件清零 + 幂等重入；</li>
 *   <li>T25（UT 面）：过期锁内复验现行 deadline——候选快照过期但库内已修宽 → 零写。</li>
 * </ul>
 */
class RunReconcilerFairnessTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();

    private RunReconciler reconciler(RunReconciler.Mode mode, int batchLimit) {
        return new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, mode, Duration.ofSeconds(30), batchLimit);
    }

    // ------------------------------------------------------------------ 场景基座

    private RcaRun activeRun(RunPurpose purpose, Instant createdAt) {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 3, RunTrigger.INITIAL,
                RcaRunState.REPORTING, Digest.sha256Of("inv-" + UUID.randomUUID()),
                createdAt, NOW, createdAt, null, null, purpose, "ut", null);
        stores.runs.insert(run);
        return run;
    }

    private void driverTask(UUID runId, RcaTaskState state, Instant leaseUntil) {
        driverTask(runId, RcaTask.NATIVE_INVESTIGATE, state, leaseUntil);
    }

    private void driverTask(UUID runId, String taskKey, RcaTaskState state, Instant leaseUntil) {
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, taskKey,
                state, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), "w1", leaseUntil, 4, 1, 3, NOW.minusSeconds(3600),
                NOW, 0));
    }

    private RcaTask finalizerTask(UUID runId, RcaTaskState state, Instant leaseUntil) {
        RcaTask task = new RcaTask(UUID.randomUUID(), runId, RcaTask.REPORT_FINALIZE,
                state, 9, NOW.minusSeconds(600), NOW.minusSeconds(600),
                NOW.plusSeconds(3600), state == RcaTaskState.LEASED ? "w9" : null,
                leaseUntil, 0, 0, 2, NOW.minusSeconds(600), NOW.minusSeconds(300), 0);
        stores.tasks.insert(task);
        return task;
    }

    private UUID incidentPointingAt(RcaRun run) {
        UUID incidentId = run.incidentId();
        stores.incidents.insert(new Incident(incidentId, "k-" + incidentId,
                IncidentStatus.FIRING, run.generation(), NOW, NOW, null,
                run.investigationHash(), null, 1, 1, 0, run.id(), NOW, NOW, NOW, NOW));
        return incidentId;
    }

    // ------------------------------------------------------------------ WC-T01：分页覆盖不饿死

    @Test
    void wc4_t01_paginationCoversAllActiveRunsAcrossTicks() {
        // 5 条活跃（批上限 2）：第 5 条最"新"——旧实现只取最老 2 条，第 5 条永不被检查
        for (int i = 0; i < 5; i++) {
            activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600 - i));
        }
        RunReconciler reconciler = reconciler(RunReconciler.Mode.ALERT_ONLY, 2);

        int first = reconciler.scanOnce();
        int second = reconciler.scanOnce();
        int third = reconciler.scanOnce();

        assertThat(first).isEqualTo(2);
        assertThat(second).isEqualTo(2);
        assertThat(third).as("尾部余量条数").isEqualTo(1);
        // 回卷：下轮从头（5 条仍活跃 → 再扫 2 条）
        assertThat(reconciler.scanOnce()).isEqualTo(2);
    }

    @Test
    void wc4_t01_expiredNewestRunIsReachedWithinFullCoverageCycle() {
        for (int i = 0; i < 4; i++) {
            activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600 - i));
        }
        // 最新一条已过可信硬期限（旧实现 51 条饥饿的缩影）
        RcaRun expired = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(1));
        driverTask(expired.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));
        stores.runs.fixReconcileDeadlineIfAbsent(expired.id(), NOW.minusSeconds(1));

        RunReconciler reconciler = reconciler(RunReconciler.Mode.AUTO_EXPIRE, 2);
        reconciler.scanOnce();
        reconciler.scanOnce();
        assertThat(stores.runs.findById(expired.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING); // 尚未轮到（批 2×2 只覆盖前 4）
        reconciler.scanOnce(); // 第三批 = 尾部 1 条 = 最新那条
        assertThat(stores.runs.findById(expired.id()).orElseThrow().state())
                .as("完整覆盖周期内最新过期 Run 必被裁决").isEqualTo(RcaRunState.EXPIRED);
    }

    // ------------------------------------------------------------------ WC-T02：同 created_at + 异常条

    @Test
    void wc4_t02_sameCreatedAtPagesByStableIdOrder() {
        Instant sameCreated = NOW.minusSeconds(600);
        for (int i = 0; i < 4; i++) {
            activeRun(RunPurpose.PRODUCTION, sameCreated);
        }
        RunReconciler reconciler = reconciler(RunReconciler.Mode.ALERT_ONLY, 2);

        assertThat(reconciler.scanOnce()).isEqualTo(2);
        assertThat(reconciler.scanOnce()).isEqualTo(2);
        // 同 created_at：id 次序翻页无重复无遗漏（4 条活跃每轮恰被决策一次）
        assertThat(reconciler.scanOnce()).isZero();
        assertThat(reconciler.scanOnce()).as("回卷后重扫全体").isEqualTo(2);
    }

    @Test
    void wc4_t02_failingCandidateDoesNotStallCursor() {
        for (int i = 0; i < 4; i++) {
            activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600 - i));
        }
        // 毒化第 2 条的铸造路径（材料无关——SAFE_RECOVER 下 DEAD driver + 无材料不铸造，
        // 这里直接让事件面按 runId 定点爆炸：任何写路径都会经过 events.append）
        RcaRun poisonedRun = stores.runs.all().get(1);
        driverTask(poisonedRun.id(), RcaTaskState.DEAD, null);
        RcaEventAppender poisoned = new RcaEventAppender() {
            @Override
            public long append(UUID runId, EventDraft draft) {
                if (runId.equals(poisonedRun.id())) {
                    throw new IllegalStateException("wc-t02 定点故障");
                }
                return 0;
            }

            @Override
            public long appendIndependent(UUID runId, EventDraft draft) {
                return append(runId, draft);
            }
        };
        RunReconciler reconciler = new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, poisoned,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, RunReconciler.Mode.AUTO_EXPIRE, Duration.ofSeconds(30), 2);
        stores.runs.fixReconcileDeadlineIfAbsent(poisonedRun.id(), NOW.minusSeconds(1));

        int decided = reconciler.scanOnce(); // 批 1 = 条 1(正常) + 条 2(爆炸)
        assertThat(decided).isEqualTo(1);
        // 游标越过故障条：下一批 = 条 3、4（若游标停在故障前，将永远重扫条 1/2）
        assertThat(reconciler.scanOnce()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ WC-T04/T05：finalizer 分类

    @Test
    void wc4_t04_inflightFinalizerStatesWaitOrReclaimWithoutRecast() {
        // READY → 等正常调度；RETRY_WAIT → 等退避；LEASED 失效租约 → 等既有回收
        RcaRun ready = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(700));
        finalizerTask(ready.id(), RcaTaskState.READY, null);
        RcaRun retry = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600));
        finalizerTask(retry.id(), RcaTaskState.RETRY_WAIT, null);
        RcaRun leasedExpired = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(500));
        finalizerTask(leasedExpired.id(), RcaTaskState.LEASED, NOW.minusSeconds(30));
        RcaRun leasedLive = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(400));
        finalizerTask(leasedLive.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));

        int decided = reconciler(RunReconciler.Mode.SAFE_RECOVER, 50).scanOnce();

        assertThat(decided).isEqualTo(4);
        assertThat(stores.tasks.all().stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey())).count())
                .as("在飞 finalizer 不重铸（每 run 仍恰一条）").isEqualTo(4);
        assertThat(stores.rcaEvents.all()).isEmpty(); // 等待零写
    }

    @Test
    void wc4_t05_terminalFinalizerStopsWaitingAndNeverRecasts() {
        // DEAD / CANCELLED / DONE 各一，材料完整 + SAFE_RECOVER——材料完整下无
        // finalizer 会铸（sr05 语义），有终态 finalizer 绝不重铸（预算不绕过）、
        // 不推进 Run（人工接管语义）
        for (RcaTaskState state : List.of(RcaTaskState.DEAD, RcaTaskState.CANCELLED,
                RcaTaskState.DONE)) {
            RcaRun run = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(700));
            driverTask(run.id(), RcaTaskState.DEAD, null);
            finalizerTask(run.id(), state, null);
            committedMaterials(run.id());
        }

        int decided = reconciler(RunReconciler.Mode.SAFE_RECOVER, 50).scanOnce();

        assertThat(decided).isEqualTo(3);
        assertThat(stores.tasks.all().stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey())).count())
                .as("终态 finalizer 不重铸（预算不绕过）").isEqualTo(3);
        assertThat(stores.rcaEvents.all()).as("分类零写（人工接管）").isEmpty();
        stores.runs.all().forEach(run ->
                assertThat(run.state()).isEqualTo(RcaRunState.REPORTING));
    }

    /** 验证通过的终态调查行（材料预提交面，同 RunReconcilerTest.committedMaterials） */
    private void committedMaterials(UUID runId) {
        UUID attemptId = UUID.randomUUID();
        stores.investigations.insertStartedIfAbsent(
                com.objwww.pr.control.alert.domain.model.InvestigationResult.started(
                        attemptId, attemptId, runId, 3, 2, null, NOW.minusSeconds(600)));
        stores.investigations.finishTerminal(new com.objwww.pr.control.alert.domain.model
                .InvestigationResult(attemptId, attemptId, runId, 3, 2,
                com.objwww.pr.control.alert.domain.model.ExecutionStatus.SUCCEEDED,
                com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED,
                null, "{\"schema_version\":\"2\",\"summary\":\"s\"}",
                "ab/" + UUID.randomUUID(), Digest.sha256Of("raw"), null, "test-model", null,
                NOW.minusSeconds(600), NOW.minusSeconds(599)));
    }

    @Test
    void wc4_t05_classifierDecisionTable() {
        // 分类器直测（§6.2 决策表封闭断言，不靠间接零写推断）
        UUID runId = UUID.randomUUID();
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.READY)), NOW))
                .contains(RunReconciler.Decision.WAIT_BACKOFF);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.RETRY_WAIT)), NOW))
                .contains(RunReconciler.Decision.WAIT_BACKOFF);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.LEASED, NOW.plusSeconds(300))), NOW))
                .contains(RunReconciler.Decision.WAIT_ACTIVE_LEASE);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.LEASED, NOW.minusSeconds(1))), NOW))
                .contains(RunReconciler.Decision.WAIT_RECLAIM);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.DEAD)), NOW))
                .contains(RunReconciler.Decision.RECOVERY_EXHAUSTED);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.CANCELLED)), NOW))
                .contains(RunReconciler.Decision.RECOVERY_EXHAUSTED);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(task(runId, RcaTaskState.DONE)), NOW))
                .contains(RunReconciler.Decision.FINALIZER_DONE_RUN_OPEN);
        assertThat(RunReconciler.classifyFinalizer(
                List.of(new RcaTask(UUID.randomUUID(), runId, RcaTask.NATIVE_INVESTIGATE,
                        RcaTaskState.READY, 3, NOW, NOW, NOW.plusSeconds(3600),
                        null, null, 0, 0, 2, NOW, NOW, 0)), NOW))
                .as("无 finalizer → empty（继续材料/孤立链）")
                .isEmpty();
        // 多轮铸造：全终态时最新一条（updatedAt 最大）定收敛事实
        RcaTask olderDone = taskAt(runId, RcaTaskState.DONE, NOW.minusSeconds(500));
        RcaTask newerDead = taskAt(runId, RcaTaskState.DEAD, NOW.minusSeconds(100));
        assertThat(RunReconciler.classifyFinalizer(List.of(olderDone, newerDead), NOW))
                .contains(RunReconciler.Decision.RECOVERY_EXHAUSTED);
    }

    // ------------------------------------------------------------------ WC-T22/T27：终态清理通道

    @Test
    void wc4_t22_t27_terminalRunOpenTasksCancelledAndPointerConditionallyCleared() {
        RcaRun cancelled = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 3,
                RunTrigger.INITIAL, RcaRunState.CANCELLED,
                Digest.sha256Of("inv"), NOW.minusSeconds(3600), NOW, NOW.minusSeconds(3500),
                NOW.minusSeconds(300), "operator", RunPurpose.PRODUCTION, "ut", null);
        stores.runs.insert(cancelled);
        UUID incidentId = incidentPointingAt(cancelled);
        driverTask(cancelled.id(), RcaTask.NATIVE_INVESTIGATE, RcaTaskState.READY, null);
        driverTask(cancelled.id(), RcaTask.HOLMES_INVESTIGATE, RcaTaskState.LEASED,
                NOW.plusSeconds(300)); // 在飞执行资格撤销
        driverTask(cancelled.id(), "UT_AUX_TASK", RcaTaskState.DONE, null); // 已终态不动

        int decided = reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();

        assertThat(decided).as("终态 Run 不进活跃通道").isZero();
        assertThat(stores.tasks.findByRunId(cancelled.id()))
                .extracting(RcaTask::state)
                .containsExactlyInAnyOrder(RcaTaskState.CANCELLED, RcaTaskState.CANCELLED,
                        RcaTaskState.DONE);
        assertThat(stores.incidents.findById(incidentId).orElseThrow().currentRcaRunId())
                .as("指针仍指本 run → 条件清零").isNull();
        long events = stores.rcaEvents.all().stream()
                .filter(e -> "RUN_TERMINAL_CLEANUP".equals(e.eventType())).count();
        assertThat(events).isEqualTo(1);

        // WC-T27（UT 面）：重入幂等——零新事件、状态零抖动
        reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();
        assertThat(stores.rcaEvents.all().stream()
                .filter(e -> "RUN_TERMINAL_CLEANUP".equals(e.eventType())).count())
                .as("重复清理不重复审计").isEqualTo(1);
    }

    @Test
    void wc4_t22_pointerOfNewerRunIsNotCleared() {
        RcaRun cancelled = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 3,
                RunTrigger.INITIAL, RcaRunState.CANCELLED,
                Digest.sha256Of("inv"), NOW.minusSeconds(3600), NOW, NOW.minusSeconds(3500),
                NOW.minusSeconds(300), "operator", RunPurpose.PRODUCTION, "ut", null);
        stores.runs.insert(cancelled);
        UUID incidentId = incidentPointingAt(cancelled);
        UUID newerRunId = UUID.randomUUID();
        stores.incidents.update(new Incident(incidentId, "k-" + incidentId,
                IncidentStatus.FIRING, 3, NOW, NOW, null, cancelled.investigationHash(),
                null, 1, 1, 0, newerRunId, NOW, NOW, NOW, NOW));
        driverTask(cancelled.id(), RcaTaskState.READY, null);

        reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();

        assertThat(stores.tasks.findByRunId(cancelled.id()))
                .extracting(RcaTask::state)
                .containsExactly(RcaTaskState.CANCELLED);
        assertThat(stores.incidents.findById(incidentId).orElseThrow().currentRcaRunId())
                .as("指针已指向新 run → 不误清").isEqualTo(newerRunId);
    }

    @Test
    void wc4_cleanupChannelNeverTouchesActiveRuns() {
        RcaRun active = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600));
        driverTask(active.id(), RcaTaskState.READY, null);

        reconciler(RunReconciler.Mode.AUTO_EXPIRE, 50).scanOnce();

        assertThat(stores.tasks.findByRunId(active.id()))
                .extracting(RcaTask::state)
                .as("活跃 Run 的任务不受清理通道影响（无 deadline 不过期）")
                .containsExactly(RcaTaskState.READY);
    }

    // ------------------------------------------------------------------ WC-T25（UT 面）：锁内复验

    @Test
    void wc4_t25_expireReverifiesCurrentDeadlineInsideLock() {
        RcaRun run = activeRun(RunPurpose.PRODUCTION, NOW.minusSeconds(600));
        driverTask(run.id(), RcaTaskState.LEASED, NOW.plusSeconds(300));
        // 候选快照读到"已过期"，库内现行期限已被修宽（并发修复/重试窗口）
        stores.runs.fixReconcileDeadlineIfAbsent(run.id(), NOW.plusSeconds(1800));
        RunReconciler reconciler = new RunReconciler(
                new StaleDeadlineRuns(stores.runs, NOW.minusSeconds(1)), stores.tasks,
                stores.reports, stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, RunReconciler.Mode.AUTO_EXPIRE, Duration.ofSeconds(30), 50);

        int decided = reconciler.scanOnce();

        assertThat(decided).isEqualTo(1); // 分类 = HARD_DEADLINE_EXPIRED（按候选快照）
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .as("锁内复验现行期限未到 → 零写不误杀").isEqualTo(RcaRunState.REPORTING);
        assertThat(stores.tasks.findByRunId(run.id()))
                .extracting(RcaTask::state)
                .containsExactly(RcaTaskState.LEASED);
        assertThat(stores.rcaEvents.all()).isEmpty();
    }

    /**
     * T25 注入件：候选投影的 deadline 定点过期（模拟快照滞后），其余全委托真 fake——
     * expireRun 锁内复验读 reconcileDeadlineById（真值=未到期）应否决终止。
     */
    private static final class StaleDeadlineRuns implements RcaRunRepository {
        private final AlertInMemoryStores.Runs delegate;
        private final Instant staleDeadline;

        private StaleDeadlineRuns(AlertInMemoryStores.Runs delegate, Instant staleDeadline) {
            this.delegate = delegate;
            this.staleDeadline = staleDeadline;
        }

        @Override
        public void insert(RcaRun run) {
            delegate.insert(run);
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public boolean update(RcaRun run) {
            return delegate.update(run);
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return delegate.findActiveByIncidentId(incidentId);
        }

        @Override
        public List<RcaRun> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<RoutingView> findRoutingById(UUID id) {
            return delegate.findRoutingById(id);
        }

        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return delegate.existsNativeRunByIncidentId(incidentId);
        }

        @Override
        public OptionalLong currentRevision(UUID id) {
            return delegate.currentRevision(id);
        }

        @Override
        public List<ReconcileCandidate> findActiveForReconcileAfter(
                Instant afterCreatedAt, UUID afterId, int limit) {
            return delegate.findActiveForReconcileAfter(afterCreatedAt, afterId, limit)
                    .stream()
                    .map(c -> new ReconcileCandidate(c.id(), c.incidentId(), c.state(),
                            c.generation(), c.purpose(), c.createdAt(), c.updatedAt(),
                            staleDeadline, c.reportingStartedAt(), c.recoveryAttempts()))
                    .toList();
        }

        @Override
        public Optional<Instant> reconcileDeadlineById(UUID id) {
            return delegate.reconcileDeadlineById(id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static RcaTask task(UUID runId, RcaTaskState state) {
        return taskAt(runId, state, NOW.minusSeconds(300));
    }

    private static RcaTask task(UUID runId, RcaTaskState state, Instant leaseUntil) {
        return taskAt(runId, state, NOW.minusSeconds(300), leaseUntil);
    }

    private static RcaTask taskAt(UUID runId, RcaTaskState state, Instant updatedAt) {
        return taskAt(runId, state, updatedAt, null);
    }

    private static RcaTask taskAt(UUID runId, RcaTaskState state, Instant updatedAt,
                                  Instant leaseUntil) {
        return new RcaTask(UUID.randomUUID(), runId, RcaTask.REPORT_FINALIZE, state, 9,
                NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.plusSeconds(3600),
                null, leaseUntil, 0, 0, 2, NOW.minusSeconds(600), updatedAt, 0);
    }
}
