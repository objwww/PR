package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-02 worker：领取→预检→注入相位驱动（本批注入如实 NOT_IMPLEMENTED → FAILED，
 * 不假装注入成功）；停止面（注入前取消 CANCELLED、停止于注入执行前零副作用 FAILED）；
 * 注入 UNKNOWN 必先进恢复路径（RECOVERY_FAILED 保留占位）；崩溃孤儿两分支
 * （未触及注入重排队身份稳定 / 已触及 RECOVERY_FAILED worker_lost）。
 */
class DrillWorkerTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final List<String> ENVS = List.of("arena-195");

    // ------------------------------------------------------------------ 假件

    private static final class FakeJobs implements DrillJobRepository {
        final Map<UUID, DrillJob> byId = new LinkedHashMap<>();

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
            return byId.values().stream()
                    .filter(j -> j.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public Optional<DrillJob> findByStopKey(String stopIdempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<DrillJob> findActiveOccupant(String targetEnv, UUID excludeId) {
            return byId.values().stream()
                    .filter(j -> j.targetEnv().equals(targetEnv)
                            && j.state().holdsEnvPlaceholder()
                            && !j.id().equals(excludeId))
                    .findFirst();
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
        public Optional<DrillJob> claimNext(String workerId, Instant claimedAt) {
            Optional<DrillJob> head = byId.values().stream()
                    .filter(j -> j.state() == DrillJob.State.QUEUED)
                    .findFirst();
            // BA-114：领取即迁移 QUEUED→PRECHECK（与真 PG 单语句 CAS 同语义）
            head.ifPresent(j -> byId.put(j.id(), new DrillJob(j.id(), j.scenarioId(),
                    j.scenarioName(), j.templateDigest(), j.targetEnv(), j.operator(),
                    DrillJob.State.PRECHECK, j.outcome(), j.terminalReason(),
                    j.paramsJson(),
                    j.payloadHash(), j.idempotencyKey(), j.stopIdempotencyKey(),
                    j.stopRequestedAt(), workerId, claimedAt, j.revision() + 1,
                    j.relatedIncidentId(), j.relatedRunId(), j.createdAt(), claimedAt,
                    j.closedAt())));
            return head.map(j -> byId.get(j.id()));
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
            return byId.values().stream()
                    .filter(j -> j.workerId() != null
                            && j.claimedAt().isBefore(claimedBefore)
                            && j.state().holdsEnvPlaceholder()
                            && j.state() != DrillJob.State.RECOVERY_FAILED)
                    .toList();
        }

        @Override
        public boolean requeue(UUID id, long expectedRevision, Instant updatedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.revision() != expectedRevision
                    || (job.state() != DrillJob.State.QUEUED
                    && job.state() != DrillJob.State.PRECHECK)) {
                return false;
            }
            byId.put(id, new DrillJob(job.id(), job.scenarioId(), job.scenarioName(),
                    job.templateDigest(), job.targetEnv(), job.operator(),
                    DrillJob.State.QUEUED, null, null, job.paramsJson(),
                    job.payloadHash(), job.idempotencyKey(), job.stopIdempotencyKey(),
                    job.stopRequestedAt(), null, null, job.revision() + 1,
                    job.relatedIncidentId(), job.relatedRunId(), job.createdAt(),
                    updatedAt, null));
            return true;
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
    }

    private static final class FixedClock implements DrillWorker.DrillClock {
        Instant now = BASE;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void sleepSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private static DrillTemplateCatalog catalog(boolean ready) {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: T1
                    name: 测试场景
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 300,
                             max_resolved_wait_seconds: 600,
                             cleanup_timeout_seconds: 120}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: %s
                      reason: %s
                """.formatted(ready, ready ? "" : "注入接线未交付（DR-03/DR-04）"));
    }

    private FakeJobs jobs;
    private FakeEvents events;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
        clock = new FixedClock();
    }

    private DrillWorker worker(DrillInjectionPort port, boolean ready) {
        return new DrillWorker(jobs, events, catalog(ready), port, clock, ENVS,
                "drill-worker-1", 5, 900);
    }

    private DrillJob enqueue(boolean stopRequested) {
        DrillJob job = DrillJob.queued(UUID.randomUUID(), "T1", "测试场景",
                "0".repeat(64), "arena-195", "operator", "{}", "1".repeat(64),
                "key-" + UUID.randomUUID(), BASE);
        if (stopRequested) {
            jobs.byId.put(job.id(), job);
            jobs.requestStop(job.id(), "stop-1", BASE);
            return jobs.findById(job.id()).orElseThrow();
        }
        jobs.insert(job);
        return job;
    }

    // ------------------------------------------------------------------ 驱动

    @Test
    @DisplayName("注入如实 NOT_IMPLEMENTED：QUEUED→PRECHECK→INJECTING→FAILED，事件账齐全")
    void injectNotImplementedFailsHonestly() {
        DrillJob job = enqueue(false);
        boolean worked = worker(new DrillInjectionPort.NotImplemented(), true).tick();
        assertThat(worked).isTrue();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.FAILED);
        assertThat(after.terminalReason()).contains("INJECTION_NOT_IMPLEMENTED");
        assertThat(after.outcome()).isNull(); // 非 CLOSED 零 outcome（CLOSED≠成功）
        assertThat(after.state().holdsEnvPlaceholder()).isFalse(); // 零副作用释放占位
        List<String> transitions = events.stored.stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
        assertThat(transitions).containsExactly(
                "QUEUED→PRECHECK", "PRECHECK→INJECTING", "INJECTING→FAILED");
        assertThat(events.stored).anySatisfy(e -> assertThat(e.eventType())
                .isEqualTo(DrillEvent.EventType.PRECHECK_RESULT));
    }

    @Test
    @DisplayName("预检未过（可执行性未交付）：PRECHECK→FAILED 零注入零副作用")
    void precheckFailureStopsBeforeInjection() {
        DrillJob job = enqueue(false);
        worker(new DrillInjectionPort.NotImplemented(), false).tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.FAILED);
        assertThat(after.terminalReason()).contains("precheck_failed")
                .contains("EXECUTION_READY");
    }

    @Test
    @DisplayName("停止必先进恢复路径的零副作用面：受理即取消 → 领取落 PRECHECK 后取消收口")
    void stopBeforeInjectionCancels() {
        DrillJob job = enqueue(true);
        worker(new DrillInjectionPort.NotImplemented(), true).tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.CANCELLED);
        assertThat(after.terminalReason()).isEqualTo("cancelled_by_operator");
    }

    @Test
    @DisplayName("注入结果 UNKNOWN：必先进 RECOVERING 再到 RECOVERY_FAILED 保留占位（DU15）")
    void unknownInjectionEntersRecoveryPath() {
        DrillJob job = enqueue(false);
        worker(job2 -> DrillInjectionPort.Outcome.unknown("on 超时响应丢失"), true).tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("ACTION_UNKNOWN");
        assertThat(after.state().holdsEnvPlaceholder()).isTrue(); // 占位阻止下一场
        List<String> transitions = events.stored.stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
        assertThat(transitions).contains("INJECTING→RECOVERING",
                "RECOVERING→RECOVERY_FAILED");
    }

    @Test
    @DisplayName("注入 PERFORMED（未来真实接线面）：推进 OBSERVING 保持活动占位")
    void performedInjectionAdvancesToObserving() {
        DrillJob job = enqueue(false);
        worker(job2 -> DrillInjectionPort.Outcome.performed("{\"sessionId\":\"s-1\"}"),
                true).tick();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.OBSERVING);
    }

    // ------------------------------------------------------------------ 崩溃恢复

    private void makeOrphan(DrillJob.State state) {
        DrillJob job = enqueue(false);
        jobs.claimNext("lost-worker", BASE.minusSeconds(3600));
        DrillJob claimed = jobs.findById(job.id()).orElseThrow();
        DrillJob staged = claimed;
        if (state != DrillJob.State.QUEUED && state != DrillJob.State.PRECHECK) {
            // 直接摆到目标相位（夹具绕过逐步推进）
            staged = new DrillJob(claimed.id(), claimed.scenarioId(),
                    claimed.scenarioName(), claimed.templateDigest(), claimed.targetEnv(),
                    claimed.operator(), state, null, null, claimed.paramsJson(),
                    claimed.payloadHash(), claimed.idempotencyKey(),
                    claimed.stopIdempotencyKey(), claimed.stopRequestedAt(),
                    claimed.workerId(), claimed.claimedAt(), claimed.revision(),
                    claimed.relatedIncidentId(), claimed.relatedRunId(),
                    claimed.createdAt(), claimed.updatedAt(), null);
        } else if (state == DrillJob.State.PRECHECK) {
            staged = new DrillJob(claimed.id(), claimed.scenarioId(),
                    claimed.scenarioName(), claimed.templateDigest(), claimed.targetEnv(),
                    claimed.operator(), DrillJob.State.PRECHECK, null, null,
                    claimed.paramsJson(), claimed.payloadHash(), claimed.idempotencyKey(),
                    claimed.stopIdempotencyKey(), claimed.stopRequestedAt(),
                    claimed.workerId(), claimed.claimedAt(), claimed.revision(),
                    claimed.relatedIncidentId(), claimed.relatedRunId(),
                    claimed.createdAt(), claimed.updatedAt(), null);
        }
        jobs.byId.put(staged.id(), staged);
    }

    @Test
    @DisplayName("孤儿 PRECHECK（未触及注入零副作用）：重排队 QUEUED，稳定身份不换 id")
    void orphanPrecheckRequeued() {
        makeOrphan(DrillJob.State.PRECHECK);
        UUID orphanId = jobs.byId.keySet().iterator().next();
        int handled = worker(new DrillInjectionPort.NotImplemented(), true)
                .sweepOrphanedClaims();
        assertThat(handled).isEqualTo(1);
        DrillJob after = jobs.findById(orphanId).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.QUEUED);
        assertThat(after.workerId()).isNull();
        assertThat(after.id()).isEqualTo(orphanId);
    }

    @Test
    @DisplayName("孤儿 INJECTING（注入状态无法判定）：先进 RECOVERING 再 RECOVERY_FAILED "
            + "worker_lost 保留占位，不冒充现场干净")
    void orphanInjectingBecomesRecoveryFailed() {
        makeOrphan(DrillJob.State.INJECTING);
        UUID orphanId = jobs.byId.keySet().iterator().next();
        int handled = worker(new DrillInjectionPort.NotImplemented(), true)
                .sweepOrphanedClaims();
        assertThat(handled).isEqualTo(1);
        DrillJob after = jobs.findById(orphanId).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("worker_lost");
        assertThat(after.state().holdsEnvPlaceholder()).isTrue();
        List<String> transitions = events.stored.stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
        assertThat(transitions).containsExactly(
                "INJECTING→RECOVERING", "RECOVERING→RECOVERY_FAILED");
    }

    @Test
    @DisplayName("孤儿 VERIFYING（本在恢复路径）：直落 RECOVERY_FAILED 保留占位")
    void orphanVerifyingBecomesRecoveryFailed() {
        makeOrphan(DrillJob.State.VERIFYING);
        UUID orphanId = jobs.byId.keySet().iterator().next();
        worker(new DrillInjectionPort.NotImplemented(), true).sweepOrphanedClaims();
        DrillJob after = jobs.findById(orphanId).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("worker_lost");
    }
}
