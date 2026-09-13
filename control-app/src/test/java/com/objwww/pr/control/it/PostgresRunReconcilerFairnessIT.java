package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.RunReconciler;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WC-4 真面（195 PG，方案 v2 §6/§5.3）：keyset 分页公平（T01/T02）、legacy 无期限
 * 不误杀（T24）、锁内复验现行 deadline（T25）、终态清理通道幂等（T27）。
 * UT 面（RunReconcilerFairnessTest）已锁决策表与游标语义；本 IT 只钉真 PG 的
 * keyset SQL/V109 索引/条件 UPDATE/join 清理面。
 */
class PostgresRunReconcilerFairnessIT extends PostgresITBase {

    private JdbcClient jdbc;
    private RcaRunRepository runs;
    private RcaTaskRepository tasks;
    private IncidentRepository incidents;
    private InvestigationResultRepository investigations;
    private RcaReportRepository reports;
    private PostgresRcaEventAppender events;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        tasks = new PostgresRcaTaskRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        investigations = new PostgresInvestigationResultRepository(jdbc);
        reports = new PostgresRcaReportRepository(jdbc);
        events = new PostgresRcaEventAppender(jdbc, controlTx, requiresNew());
    }

    private RunReconciler reconciler(RunReconciler.Mode mode, int batchLimit) {
        return new RunReconciler(runs, tasks, reports, investigations, incidents, events,
                controlTx, SlaPolicy.defaults(), AlertClock.system(), mode,
                Duration.ofSeconds(30), batchLimit);
    }

    // ------------------------------------------------ WC-T01：60 条活跃，最老批不饿死最新条

    @Test
    @DisplayName("WC-T01 60 活跃/批 50：最新过期 Run 在完整覆盖周期（两批）内被裁决")
    void wc4_t01_keysetPaginationReachesNewestExpiredRunWithinCoverageCycle() {
        Instant base = Instant.now().minus(Duration.ofMinutes(60));
        for (int i = 0; i < 59; i++) {
            RcaRun run = insertRunningRun("wc-t01-" + i, base.plusSeconds(i));
            insertDriverTask(run.id(), RcaTaskState.LEASED, Instant.now().plusSeconds(300));
        }
        RcaRun target = insertRunningRun("wc-t01-target", base.plusSeconds(59));
        insertDriverTask(target.id(), RcaTaskState.LEASED, Instant.now().plusSeconds(300));
        runs.fixReconcileDeadlineIfAbsent(target.id(), Instant.now().minusSeconds(1));
        RunReconciler reconciler = reconciler(RunReconciler.Mode.AUTO_EXPIRE, 50);

        int first = reconciler.scanOnce();
        assertThat(first).isEqualTo(50);
        assertThat(runs.findById(target.id()).orElseThrow().state())
                .as("最新条尚在第二批（旧实现将永不被检查）").isEqualTo(RcaRunState.RUNNING);

        int second = reconciler.scanOnce();
        assertThat(second).isEqualTo(10);
        assertThat(runs.findById(target.id()).orElseThrow().state())
                .as("完整覆盖周期内最新过期 Run 必被裁决").isEqualTo(RcaRunState.EXPIRED);
        assertThat(countTasksInState(target.id(), RcaTaskState.CANCELLED)).isEqualTo(1);
        assertThat(eventCount(target.id(), "RUN_EXPIRED")).isEqualTo(1);
    }

    // ------------------------------------------------ WC-T02：同 created_at 按 id 稳定翻页

    @Test
    @DisplayName("WC-T02 全体同 created_at：id 次序稳定翻页无重无漏（末位 id 最大者在末批）")
    void wc4_t02_sameCreatedAtPagesStableByIdOrder() {
        Instant sameCreated = Instant.now().minus(Duration.ofMinutes(30))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        java.util.List<UUID> runIds = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // 定序 id：MSB 同值、LSB=i（正数）——Java 有符号 compareTo 与 PG uuid 无符号
            // 字节序对随机 v4 id 会分叉（LSB 高位 1 在 Java 为负、在 PG 为大），
            // 翻页稳定性断言必须建立在两种序一致的 id 集上
            UUID id = UUID.fromString("00000000-0000-4000-0000-"
                    + String.format("%012d", i));
            RcaRun run = insertRunningRunWithId("wc-t02-" + i, sameCreated, id);
            insertDriverTask(run.id(), RcaTaskState.LEASED, Instant.now().plusSeconds(300));
            runIds.add(run.id());
        }
        // 同 created_at 的"最后一条" = 本批 id 最大者：过期锚定在它身上（id 次序错乱即漏扫）
        UUID lastId = runIds.stream().max(UUID::compareTo).orElseThrow();
        runs.fixReconcileDeadlineIfAbsent(lastId, Instant.now().minusSeconds(1));
        RunReconciler reconciler = reconciler(RunReconciler.Mode.AUTO_EXPIRE, 10);

        assertThat(reconciler.scanOnce()).isEqualTo(10);
        assertThat(reconciler.scanOnce()).isEqualTo(10);
        assertThat(runs.findById(lastId).orElseThrow().state()).isEqualTo(RcaRunState.RUNNING);
        assertThat(reconciler.scanOnce()).isEqualTo(10);
        assertThat(runs.findById(lastId).orElseThrow().state())
                .as("同 created_at 下 id 最大者在末批被裁决").isEqualTo(RcaRunState.EXPIRED);
        // 第四批 = 尾后空批（游标回卷触发面，0 条）；第五批 = 回卷重扫（29 条仍活跃 → 批 10）
        assertThat(reconciler.scanOnce()).as("尾后空批先回卷游标").isZero();
        assertThat(reconciler.scanOnce()).isEqualTo(10);
    }

    // ------------------------------------------------ WC-T24：deadline null 的 legacy 不凭年龄杀

    @Test
    @DisplayName("WC-T24 deadline 为 null 的 legacy Run 长期无更新：只观察不误杀零写")
    void wc4_t24_legacyWithoutDeadlineIsNeverKilledByAge() {
        RcaRun legacy = insertRunningRun("wc-t24",
                Instant.now().minus(Duration.ofDays(9)));
        insertDriverTask(legacy.id(), RcaTaskState.LEASED, Instant.now().plusSeconds(300));
        // reconcile_deadline_at 保持 NULL（不 fix）

        int decided = reconciler(RunReconciler.Mode.AUTO_EXPIRE, 50).scanOnce();

        assertThat(decided).isEqualTo(1); // 被检查（分类），但——
        assertThat(runs.findById(legacy.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.RUNNING);
        assertThat(eventCount(legacy.id(), "RUN_EXPIRED")).isZero();
        assertThat(countTasksInState(legacy.id(), RcaTaskState.CANCELLED)).isZero();
    }

    // ------------------------------------------------ WC-T25：锁内复验现行 deadline

    @Test
    @DisplayName("WC-T25 候选快照过期但库内现行期限已修宽：锁内复验否决，零恢复零过期副作用")
    void wc4_t25_expireReverifiesCurrentDeadlineOnRealPg() {
        RcaRun run = insertRunningRun("wc-t25", Instant.now().minus(Duration.ofMinutes(30)));
        insertDriverTask(run.id(), RcaTaskState.LEASED, Instant.now().plusSeconds(300));
        runs.fixReconcileDeadlineIfAbsent(run.id(), Instant.now().plusSeconds(1800));
        RunReconciler staleSnapshot = new RunReconciler(
                new StaleDeadlineRuns((PostgresRcaRunRepository) runs,
                        Instant.now().minusSeconds(1)),
                tasks, reports, investigations, incidents, events, controlTx,
                SlaPolicy.defaults(), AlertClock.system(),
                RunReconciler.Mode.AUTO_EXPIRE,
                Duration.ofSeconds(30), 50);

        int decided = staleSnapshot.scanOnce();

        assertThat(decided).isEqualTo(1); // 按候选快照分类 = HARD_DEADLINE_EXPIRED
        assertThat(runs.findById(run.id()).orElseThrow().state())
                .as("锁内现行 deadline 未到 → 零写").isEqualTo(RcaRunState.RUNNING);
        assertThat(eventCount(run.id(), "RUN_EXPIRED")).isZero();
        assertThat(countTasksInState(run.id(), RcaTaskState.CANCELLED)).isZero();
    }

    // ------------------------------------------------ WC-T27：终态清理幂等（含部分清理后重入）

    @Test
    @DisplayName("WC-T27 终态 Run 清理：资格撤销+指针条件清零+单事件；部分清理/重入均幂等")
    void wc4_t27_terminalCleanupIsIdempotentAndCompletesPartialWork() {
        // (a) 全量清理 + 指针清零
        RcaRun cancelled = insertRunInState("wc-t27-a", RcaRunState.CANCELLED,
                Instant.now().minus(Duration.ofMinutes(30)));
        seedIncidentPointerAt(cancelled);
        insertDriverTask(cancelled.id(), RcaTaskState.READY, null);
        insertDriverTaskWithKey(cancelled.id(), "UT_AUX_A",
                RcaTaskState.LEASED, Instant.now().plusSeconds(300));

        int decidedActive = reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();

        assertThat(decidedActive).as("终态 Run 不进活跃通道").isZero();
        assertThat(countTasksInState(cancelled.id(), RcaTaskState.CANCELLED)).isEqualTo(2);
        assertThat(eventCount(cancelled.id(), "RUN_TERMINAL_CLEANUP")).isEqualTo(1);
        assertThat(incidents.findById(cancelled.incidentId()).orElseThrow()
                .currentRcaRunId()).isNull();

        // (b) 模拟批次中途死亡：另一终态 Run 预先手动清了一半任务，重启后清理补完且不重复审计
        RcaRun halfCleaned = insertRunInState("wc-t27-b", RcaRunState.EXPIRED,
                Instant.now().minus(Duration.ofMinutes(20)));
        seedIncidentPointerAt(halfCleaned);
        RcaTask leftoverReady = insertDriverTaskWithKey(halfCleaned.id(), "UT_AUX_B1",
                RcaTaskState.READY, null);
        RcaTask alreadyCancelled = insertDriverTaskWithKey(halfCleaned.id(), "UT_AUX_B2",
                RcaTaskState.READY, null);
        tasks.update(alreadyCancelled.withState(RcaTaskState.CANCELLED, Instant.now()));

        reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();

        assertThat(tasks.findById(leftoverReady.id()).orElseThrow().state())
                .as("重启后补完剩余任务").isEqualTo(RcaTaskState.CANCELLED);
        assertThat(eventCount(halfCleaned.id(), "RUN_TERMINAL_CLEANUP"))
                .as("只对真取消的余量落一次事件").isEqualTo(1);
        // 重入：全终态 → 零新事件、零状态抖动
        reconciler(RunReconciler.Mode.ALERT_ONLY, 50).scanOnce();
        assertThat(eventCount(halfCleaned.id(), "RUN_TERMINAL_CLEANUP")).isEqualTo(1);
        assertThat(eventCount(cancelled.id(), "RUN_TERMINAL_CLEANUP")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 种子与助手

    private RcaRun insertRunningRun(String tag, Instant createdAt) {
        return insertRunInState(tag, RcaRunState.RUNNING, createdAt);
    }

    /** T02 定序 id 专用（Java/PG 双序一致的 id 集，见用例内注释） */
    private RcaRun insertRunningRunWithId(String tag, Instant createdAt, UUID id) {
        Instant now = Instant.now();
        UUID incidentId = UUID.randomUUID();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(35)),
                now.minus(Duration.ofMinutes(35)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(35)), now.minus(Duration.ofMinutes(35)), now, now));
        RcaRun run = new RcaRun(id, incidentId, 0, RunTrigger.INITIAL, RcaRunState.RUNNING,
                Digest.sha256Of("run-" + tag), createdAt, now, createdAt, null, null,
                RunPurpose.PRODUCTION, "wc-it", null);
        runs.insert(run);
        return run;
    }

    private RcaRun insertRunInState(String tag, RcaRunState state, Instant createdAt) {
        UUID incidentId = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(35)),
                now.minus(Duration.ofMinutes(35)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(35)), now.minus(Duration.ofMinutes(35)), now, now));
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("run-" + tag), createdAt, now,
                state == RcaRunState.QUEUED ? null : createdAt,
                state.isActive() ? null : now, null,
                RunPurpose.PRODUCTION, "wc-it", null);
        runs.insert(run);
        return run;
    }

    private void seedIncidentPointerAt(RcaRun run) {
        Incident incident = incidents.findById(run.incidentId()).orElseThrow();
        incidents.update(new Incident(incident.id(), incident.incidentKey(),
                incident.status(), incident.generation(), incident.episodeStartedAt(),
                incident.lastFiringStartsAt(), incident.resolvedAt(),
                incident.lastInvestigationHash(), incident.pendingInvestigationHash(),
                incident.receivedCount(), incident.distinctEventCount(),
                incident.notificationCount(), run.id(), incident.firstSeenAt(),
                incident.lastEventAt(), incident.createdAt(), Instant.now(),
                incident.waitingReason()));
    }

    private void insertDriverTask(UUID runId, RcaTaskState state, Instant leaseUntil) {
        insertDriverTaskWithKey(runId, RcaTask.NATIVE_INVESTIGATE, state, leaseUntil);
    }

    private RcaTask insertDriverTaskWithKey(UUID runId, String taskKey, RcaTaskState state,
                                            Instant leaseUntil) {
        Instant now = Instant.now();
        RcaTask task = new RcaTask(UUID.randomUUID(), runId, taskKey, state, 3,
                now.minus(Duration.ofMinutes(29)), now.minus(Duration.ofMinutes(29)),
                now.plusSeconds(3600), state == RcaTaskState.LEASED ? "worker-a" : null,
                leaseUntil, 0, 1, 3, now.minus(Duration.ofMinutes(29)), now, 0);
        tasks.insert(task);
        return task;
    }

    private int countTasksInState(UUID runId, RcaTaskState state) {
        return (int) tasks.findByRunId(runId).stream()
                .filter(t -> t.state() == state).count();
    }

    private long eventCount(UUID runId, String eventType) {
        return adminJdbc.sql(
                        "SELECT count(*) FROM rca_event WHERE run_id=:r AND event_type=:t")
                .param("r", runId).param("t", eventType).query(Long.class).single();
    }

    private org.springframework.transaction.support.TransactionTemplate requiresNew() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * T25 注入件：候选投影的 deadline 定点过期（模拟快照滞后/并发修宽前的读取），
     * 其余全委托真 PG 实现——expireRun 锁内复验读现行值应否决终止。
     */
    private static final class StaleDeadlineRuns implements RcaRunRepository {
        private final PostgresRcaRunRepository delegate;
        private final Instant staleDeadline;

        private StaleDeadlineRuns(PostgresRcaRunRepository delegate, Instant staleDeadline) {
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
}
