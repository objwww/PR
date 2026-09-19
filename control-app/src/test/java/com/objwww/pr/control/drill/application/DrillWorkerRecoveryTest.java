package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-04 worker 恢复/核验相位驱动（§7.4/§7.5）：OBSERVING 观察窗期满或受理停止 →
 * RECOVERING + 立即恢复；RECOVERING 三态（RECOVERED→VERIFYING / FAILED→
 * RECOVERY_FAILED 诚实卡因 / UNKNOWN 保持下拍重试）；VERIFYING 三态（VERIFIED→
 * CLOSED+outcome 真值 / FAILED→RECOVERY_FAILED / PENDING 保持）；恢复窗口截止 =
 * 重试上限（不许死循环）；人工重试意图消费（RECOVERY_FAILED→RECOVERING）。
 * 假件全内存（DrillWorkerTest 同习语），恢复/核验端口桩三态可控。
 */
class DrillWorkerRecoveryTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final List<String> ENVS = List.of("arena-195");

    // ------------------------------------------------------------------ 假件

    private static final class FakeJobs implements DrillJobRepository {
        final Map<UUID, DrillJob> byId = new LinkedHashMap<>();
        final Set<UUID> retryRequested = new LinkedHashSet<>();

        @Override
        public void insert(DrillJob job) {
            byId.put(job.id(), job);
        }

        @Override
        public Optional<DrillJob> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DrillJob> findByIdempotencyKey(String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DrillJob> findByStopKey(String stopIdempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DrillJob> findActiveOccupant(String targetEnv, UUID excludeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DrillJob> list(String state, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countActive() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countRecoveryFailed() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean requestStop(UUID id, String stopIdempotencyKey,
                                   Instant stopRequestedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.stopRequestedAt() != null) {
                return false;
            }
            byId.put(id, new DrillJob(job.id(), job.scenarioId(), job.scenarioName(),
                    job.templateDigest(), job.targetEnv(), job.operator(), job.state(),
                    job.outcome(), job.terminalReason(), job.paramsJson(),
                    job.payloadHash(), job.idempotencyKey(), stopIdempotencyKey,
                    stopRequestedAt, job.workerId(), job.claimedAt(), job.revision(),
                    job.relatedIncidentId(), job.relatedRunId(), job.createdAt(),
                    job.updatedAt(), job.closedAt()));
            return true;
        }

        @Override
        public boolean requestRetry(UUID id, Instant retryRequestedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.state() != DrillJob.State.RECOVERY_FAILED
                    || retryRequested.contains(id)) {
                return false;
            }
            retryRequested.add(id);
            return true;
        }

        @Override
        public List<DrillJob> findRetryRequests() {
            return byId.values().stream()
                    .filter(j -> j.state() == DrillJob.State.RECOVERY_FAILED
                            && retryRequested.contains(j.id()))
                    .toList();
        }

        @Override
        public boolean consumeRetry(UUID id, long expectedRevision, String workerId,
                                    Instant now) {
            DrillJob job = byId.get(id);
            if (job == null || job.state() != DrillJob.State.RECOVERY_FAILED
                    || job.revision() != expectedRevision
                    || !retryRequested.contains(id)) {
                return false;
            }
            retryRequested.remove(id);
            byId.put(id, new DrillJob(job.id(), job.scenarioId(), job.scenarioName(),
                    job.templateDigest(), job.targetEnv(), job.operator(),
                    DrillJob.State.RECOVERING, null, null, job.paramsJson(),
                    job.payloadHash(), job.idempotencyKey(), job.stopIdempotencyKey(),
                    job.stopRequestedAt(), workerId, now, job.revision() + 1,
                    job.relatedIncidentId(), job.relatedRunId(), job.createdAt(),
                    now, null));
            return true;
        }

        @Override
        public Optional<DrillJob> claimNext(String workerId, Instant claimedAt) {
            return Optional.empty(); // 本测试无 QUEUED 作业
        }

        @Override
        public boolean advance(UUID id, long expectedRevision, DrillJob.State from,
                               DrillJob.State to, Instant updatedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.state() != from || job.revision() != expectedRevision) {
                return false;
            }
            byId.put(id, job.advanced(to, null, null, null, updatedAt));
            return true;
        }

        @Override
        public boolean finalize(UUID id, long expectedRevision, DrillJob.State from,
                                DrillJob.State to, String terminalReason, String outcome,
                                Instant closedAt, Instant updatedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.state() != from || job.revision() != expectedRevision) {
                return false;
            }
            byId.put(id, job.advanced(to, terminalReason, outcome, closedAt, updatedAt));
            return true;
        }

        @Override
        public List<DrillJob> findOrphanedClaims(Instant claimedBefore) {
            return List.of();
        }

        @Override
        public boolean requeue(UUID id, long expectedRevision, Instant updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DrillJob> findActiveInStates(List<DrillJob.State> states) {
            return byId.values().stream()
                    .filter(j -> states.contains(j.state()))
                    .toList();
        }

        @Override
        public boolean linkRelated(UUID id, long expectedRevision, UUID incidentId,
                                   UUID runId, Instant updatedAt) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeEvents implements DrillEventRepository {
        final List<DrillEvent> stored = new ArrayList<>();

        @Override
        public void insert(DrillEvent event) {
            stored.add(event);
        }

        @Override
        public List<DrillEvent> listByDrill(UUID drillId) {
            return stored.stream().filter(e -> e.drillId().equals(drillId)).toList();
        }

        @Override
        public List<DrillEvent> listByDrillAfter(UUID drillId, long afterSeq, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FixedClock implements DrillWorker.DrillClock {
        Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void sleepSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    /** 恢复/核验端口桩：恢复三态与核验三态各自按队列出招，计数暴露调用次数 */
    private static final class StubRecovery implements DrillRecoveryPort {
        final Deque<RecoverOutcome> recoverOutcomes = new ArrayDeque<>();
        final Deque<VerifyOutcome> verifyOutcomes = new ArrayDeque<>();
        int recoverCalls;
        int verifyCalls;

        @Override
        public RecoverOutcome recover(DrillJob job) {
            recoverCalls++;
            return recoverOutcomes.isEmpty()
                    ? RecoverOutcome.recovered("stub_recovered") : recoverOutcomes.poll();
        }

        @Override
        public VerifyOutcome verify(DrillJob job) {
            verifyCalls++;
            return verifyOutcomes.isEmpty()
                    ? VerifyOutcome.verified("stub_verified") : verifyOutcomes.poll();
        }
    }

    /** 测试目录：T1 声明症状码（outcome 推导面），恢复窗口 deadline 50s 便于截止用例 */
    private static DrillTemplateCatalog catalog() {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: T1
                    name: 测试场景
                    driver: FlagdScenarioDriver
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 300,
                             max_resolved_wait_seconds: 600,
                             cleanup_timeout_seconds: 120}
                    recovery: {probe_seconds: 10, deadline_seconds: 50}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
    }

    private FakeJobs jobs;
    private FakeEvents events;
    private FixedClock clock;
    private StubRecovery recovery;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
        clock = new FixedClock(BASE);
        recovery = new StubRecovery();
    }

    private DrillWorker worker() {
        return new DrillWorker(jobs, events, catalog(),
                new DrillInjectionPort.NotImplemented(), recovery, clock, ENVS,
                "drill-worker-1", 5, 900, DrillCorrelationPort.disabled(), null,
                new DrillExecutionPolicy(true, ENVS), sid -> List.of());
    }

    /** 摆一个指定相位的活动作业（冻结 durationSeconds=100；注入事件于 BASE） */
    private DrillJob stage(DrillJob.State state) {
        DrillJob job = new DrillJob(UUID.randomUUID(), "T1", "测试场景", "0".repeat(64),
                "arena-195", "operator", state, null, null,
                "{\"durationSeconds\":100}", "1".repeat(64),
                "key-" + UUID.randomUUID(), null, null, "drill-worker-1", BASE, 0,
                null, null, BASE, BASE, null);
        jobs.insert(job);
        if (state == DrillJob.State.OBSERVING) {
            events.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.INJECTING,
                    DrillJob.State.OBSERVING, "drill-worker-1", "{}", BASE));
        } else {
            events.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.OBSERVING,
                    state, "drill-worker-1", "{}", BASE));
        }
        return job;
    }

    private List<String> transitionsOf(UUID drillId) {
        return events.stored.stream()
                .filter(e -> e.drillId().equals(drillId)
                        && e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
    }

    // ------------------------------------------------------------------ 观察窗推进

    @Test
    @DisplayName("OBSERVING 观察窗期满 → 自动推进 RECOVERING 并立即恢复"
            + "（RECOVERED→VERIFYING）")
    void observingElapsedDrivesRecovery() {
        DrillJob job = stage(DrillJob.State.OBSERVING);
        // 窗口 = 注入时刻 + durationSeconds(100) + maxFiringWait(300)
        clock.now = BASE.plusSeconds(399);
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.OBSERVING); // 未期满不动
        assertThat(recovery.recoverCalls).isZero();

        clock.now = BASE.plusSeconds(400);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.VERIFYING);
        assertThat(recovery.recoverCalls).isEqualTo(1);
        assertThat(transitionsOf(job.id())).contains(
                "OBSERVING→RECOVERING", "RECOVERING→VERIFYING");
    }

    @Test
    @DisplayName("OBSERVING 受理停止（窗口未期满）→ 必先进 RECOVERING 恢复路径")
    void observingStopDrivesRecovery() {
        DrillJob job = stage(DrillJob.State.OBSERVING);
        jobs.requestStop(job.id(), "stop-1", BASE.plusSeconds(10));
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.VERIFYING);
        assertThat(recovery.recoverCalls).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 恢复三态

    @Test
    @DisplayName("恢复 UNKNOWN：保持 RECOVERING 下拍重试（不占位卡死），恢复后推进 VERIFYING")
    void recoverUnknownRetriesNextTick() {
        DrillJob job = stage(DrillJob.State.RECOVERING);
        recovery.recoverOutcomes.add(DrillRecoveryPort.RecoverOutcome.unknown(
                "flag_state_unknown: read_failed"));
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.RECOVERING); // 保持重试，不死循环不落占位
        assertThat(recovery.recoverCalls).isEqualTo(1);

        worker().tick(); // 缺省 RECOVERED
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.VERIFYING);
        assertThat(recovery.recoverCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("恢复 FAILED（确定不可恢复，如 flagd 冲突不覆盖）→ RECOVERY_FAILED 诚实卡因")
    void recoverFailedLandsRecoveryFailed() {
        DrillJob job = stage(DrillJob.State.RECOVERING);
        recovery.recoverOutcomes.add(DrillRecoveryPort.RecoverOutcome.failed(
                "flag_restore_conflict: current=90%"));
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("recovery_failed")
                .contains("flag_restore_conflict");
        assertThat(after.state().holdsEnvPlaceholder()).isTrue(); // 占位阻止下一场
        assertThat(recovery.recoverCalls).isEqualTo(1); // 确定失败不重试
    }

    @Test
    @DisplayName("恢复窗口截止 = 重试上限：超 deadline 仍 UNKNOWN → RECOVERY_FAILED"
            + "（不许死循环）")
    void recoverUnknownCappedByDeadline() {
        DrillJob job = stage(DrillJob.State.RECOVERING); // 进入相位 = BASE
        recovery.recoverOutcomes.add(DrillRecoveryPort.RecoverOutcome.unknown("x"));
        clock.now = BASE.plusSeconds(49);
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.RECOVERING);

        clock.now = BASE.plusSeconds(50); // 模板 recovery.deadline_seconds=50
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("recovery_deadline_exceeded");
        assertThat(recovery.recoverCalls).isEqualTo(1); // 截止后不再调端口
    }

    // ------------------------------------------------------------------ 核验三态

    @Test
    @DisplayName("核验 VERIFIED → CLOSED + outcome 真值（声明症状码且无关联 = FAIL；"
            + "CLOSED≠成功）；OUTCOME_RECORDED 落账")
    void verifyVerifiedClosesWithOutcome() {
        DrillJob job = stage(DrillJob.State.VERIFYING);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.CLOSED);
        assertThat(after.outcome()).isEqualTo("FAIL"); // 症状从未关联观测到，如实
        assertThat(after.closedAt()).isNotNull();
        assertThat(transitionsOf(job.id())).contains("VERIFYING→CLOSED");
        assertThat(events.stored).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(DrillEvent.EventType.OUTCOME_RECORDED);
            assertThat(e.payloadJson()).contains("\"FAIL\"");
        });
    }

    @Test
    @DisplayName("核验 VERIFIED 且症状已关联（related_incident_id）→ CLOSED + outcome=PASS")
    void verifyVerifiedWithCorrelationPasses() {
        DrillJob staged = stage(DrillJob.State.VERIFYING);
        DrillJob job = new DrillJob(staged.id(), staged.scenarioId(),
                staged.scenarioName(), staged.templateDigest(), staged.targetEnv(),
                staged.operator(), staged.state(), null, null, staged.paramsJson(),
                staged.payloadHash(), staged.idempotencyKey(),
                staged.stopIdempotencyKey(), staged.stopRequestedAt(), staged.workerId(),
                staged.claimedAt(), staged.revision(), UUID.randomUUID(), null,
                staged.createdAt(), staged.updatedAt(), null);
        jobs.byId.put(job.id(), job);
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().outcome()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("核验 PENDING（残留 firing）保持 VERIFYING 重试；超恢复窗口 → "
            + "RECOVERY_FAILED（verify_deadline_exceeded）")
    void verifyPendingCappedByDeadline() {
        DrillJob job = stage(DrillJob.State.VERIFYING); // 进入相位 = BASE
        recovery.verifyOutcomes.add(DrillRecoveryPort.VerifyOutcome.pending(
                "alerts_still_firing"));
        worker().tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.VERIFYING);
        assertThat(recovery.verifyCalls).isEqualTo(1);

        clock.now = BASE.plusSeconds(50);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("verify_deadline_exceeded");
        assertThat(recovery.verifyCalls).isEqualTo(1); // 截止后不再调端口
    }

    // ------------------------------------------------------------------ 人工重试

    @Test
    @DisplayName("人工重试消费：RECOVERY_FAILED + 意图列 → RECOVERING（清意图+刷新租约"
            + "+迁移事件），同拍恢复端口重走；无意图不动")
    void manualRetryConsumed() {
        DrillJob job = new DrillJob(UUID.randomUUID(), "T1", "测试场景", "0".repeat(64),
                "arena-195", "operator", DrillJob.State.RECOVERY_FAILED, null,
                "worker_lost", "{\"durationSeconds\":100}", "1".repeat(64),
                "key-" + UUID.randomUUID(), null, null, "drill-worker-1", BASE, 0,
                null, null, BASE, BASE, null);
        jobs.insert(job);
        worker().tick(); // 无意图：不动
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(recovery.recoverCalls).isZero();

        jobs.requestRetry(job.id(), BASE.plusSeconds(5));
        clock.now = BASE.plusSeconds(5);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.VERIFYING); // 消费+恢复同拍
        assertThat(after.claimedAt()).isEqualTo(BASE.plusSeconds(5)); // 租约刷新
        assertThat(jobs.retryRequested).isEmpty(); // 意图已清
        assertThat(transitionsOf(job.id())).containsExactly(
                "RECOVERY_FAILED→RECOVERING", "RECOVERING→VERIFYING");
        assertThat(recovery.recoverCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("人工重试后恢复窗口重新起算（相位进入时刻 = 重试消费时刻）")
    void retryRestartsRecoveryWindow() {
        DrillJob job = stage(DrillJob.State.RECOVERING);
        recovery.recoverOutcomes.add(DrillRecoveryPort.RecoverOutcome.failed("conflict"));
        clock.now = BASE.plusSeconds(10);
        worker().tick(); // → RECOVERY_FAILED（进入相位 BASE，窗口未过期，FAILED 卡因）
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.RECOVERY_FAILED);

        jobs.requestRetry(job.id(), BASE.plusSeconds(100));
        clock.now = BASE.plusSeconds(100); // 远超原窗口（BASE+50）
        worker().tick();
        // 窗口随重试重新起算：恢复端口被重调而非直接截止
        assertThat(recovery.recoverCalls).isEqualTo(2);
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.VERIFYING);
    }
}
