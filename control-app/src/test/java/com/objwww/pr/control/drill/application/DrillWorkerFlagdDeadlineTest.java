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
 * DR-05 作业级截止恢复/重启清扫（§7.4 Flagd 段）：超「claimed_at + 冻结
 * totalEstimateSeconds」仍活动中的 flagd 作业 → 必先进 RECOVERING 再
 * RECOVERY_FAILED 保留占位（恢复接线未交付，不冒充现场干净）；arena 场景、
 * 未超期、截止读不出的作业一律不动（留孤儿清扫对账）。
 */
class DrillWorkerFlagdDeadlineTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final long ESTIMATE = 4380; // 60+600+1500+2100+120
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
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean requeue(UUID id, long expectedRevision, Instant updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DrillJob> findActiveInStates(List<DrillJob.State> states) {
            return byId.values().stream().filter(j -> states.contains(j.state())).toList();
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

    private FakeJobs jobs;
    private FakeEvents events;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
    }

    /** 旧九参装配面（关联 disabled/无台账清扫）——截止对账不依赖新接线 */
    private DrillWorker worker(Instant now) {
        return new DrillWorker(jobs, events, catalog(),
                new DrillInjectionPort.NotImplemented(), new FixedClock(now), ENVS,
                "drill-worker-1", 5, 900);
    }

    private static DrillTemplateCatalog catalog() {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: F1
                    name: flagd 场景
                    driver: FlagdScenarioDriver
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 1500,
                             max_resolved_wait_seconds: 2100,
                             cleanup_timeout_seconds: 120}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                  - scenario_id: A1
                    name: arena 场景
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    symptom_codes: [ArenaDuplicateOrders]
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 300,
                             max_resolved_wait_seconds: 600,
                             cleanup_timeout_seconds: 120}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
    }

    private DrillJob stage(String scenarioId, DrillJob.State state, String paramsJson) {
        DrillJob job = new DrillJob(UUID.randomUUID(), scenarioId, scenarioId + " 场景",
                "0".repeat(64), "arena-195", "operator", state, null, null, paramsJson,
                "1".repeat(64), "key-" + UUID.randomUUID(), null, null,
                "drill-worker-1", BASE, 0, null, null, BASE, BASE, null);
        jobs.insert(job);
        return job;
    }

    private List<String> transitionsOf(UUID drillId) {
        return events.stored.stream()
                .filter(e -> e.drillId().equals(drillId)
                        && e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("flagd OBSERVING 超作业级截止 → 先进 RECOVERING 再 RECOVERY_FAILED 保留占位")
    void flagdObservingPastDeadlineEntersRecoveryPath() {
        DrillJob job = stage("F1", DrillJob.State.OBSERVING,
                "{\"totalEstimateSeconds\":" + ESTIMATE + "}");
        int handled = worker(BASE.plusSeconds(ESTIMATE + 1))
                .sweepFlagdRecoveryDeadlines();
        assertThat(handled).isEqualTo(1);
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(after.terminalReason()).contains("flagd_recovery_deadline_exceeded");
        assertThat(after.state().holdsEnvPlaceholder()).isTrue(); // 占位阻止下一场
        assertThat(transitionsOf(job.id())).containsExactly(
                "OBSERVING→RECOVERING", "RECOVERING→RECOVERY_FAILED");
    }

    @Test
    @DisplayName("flagd INJECTING 超截止（注入状态无法判定）→ 同样先进恢复路径")
    void flagdInjectingPastDeadlineEntersRecoveryPath() {
        DrillJob job = stage("F1", DrillJob.State.INJECTING,
                "{\"totalEstimateSeconds\":" + ESTIMATE + "}");
        worker(BASE.plusSeconds(ESTIMATE + 1)).sweepFlagdRecoveryDeadlines();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(transitionsOf(job.id())).containsExactly(
                "INJECTING→RECOVERING", "RECOVERING→RECOVERY_FAILED");
    }

    @Test
    @DisplayName("flagd RECOVERING 超截止（本在恢复路径）→ 直落 RECOVERY_FAILED，不越级回扫")
    void flagdRecoveringPastDeadlineFinalizesDirectly() {
        DrillJob job = stage("F1", DrillJob.State.RECOVERING,
                "{\"totalEstimateSeconds\":" + ESTIMATE + "}");
        worker(BASE.plusSeconds(ESTIMATE + 1)).sweepFlagdRecoveryDeadlines();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.RECOVERY_FAILED);
        assertThat(transitionsOf(job.id()))
                .containsExactly("RECOVERING→RECOVERY_FAILED");
    }

    @Test
    @DisplayName("flagd 未超截止 → 不动")
    void flagdWithinDeadlineUntouched() {
        DrillJob job = stage("F1", DrillJob.State.OBSERVING,
                "{\"totalEstimateSeconds\":" + ESTIMATE + "}");
        int handled = worker(BASE.plusSeconds(100)).sweepFlagdRecoveryDeadlines();
        assertThat(handled).isEqualTo(0);
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.OBSERVING);
        assertThat(events.stored).isEmpty();
    }

    @Test
    @DisplayName("arena 场景超截止 → 不动（TTL 保障与恢复接线归 DR-04 面）")
    void arenaPastDeadlineUntouched() {
        DrillJob job = stage("A1", DrillJob.State.OBSERVING,
                "{\"totalEstimateSeconds\":1620}");
        int handled = worker(BASE.plusSeconds(1621)).sweepFlagdRecoveryDeadlines();
        assertThat(handled).isEqualTo(0);
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.OBSERVING);
    }

    @Test
    @DisplayName("截止读不出（params 缺 totalEstimateSeconds）→ 不动，留孤儿清扫对账")
    void unreadableDeadlineSkipped() {
        DrillJob job = stage("F1", DrillJob.State.OBSERVING, "{}");
        int handled = worker(BASE.plusSeconds(ESTIMATE + 1))
                .sweepFlagdRecoveryDeadlines();
        assertThat(handled).isEqualTo(0);
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.OBSERVING);
    }

    @Test
    @DisplayName("清扫经 tick 驱动（worker 重启后首拍即对账）：无 QUEUED 可领也算有活")
    void sweepRunsInsideTick() {
        DrillJob job = stage("F1", DrillJob.State.OBSERVING,
                "{\"totalEstimateSeconds\":" + ESTIMATE + "}");
        boolean worked = worker(BASE.plusSeconds(ESTIMATE + 1)).tick();
        assertThat(worked).isTrue();
        assertThat(jobs.findById(job.id()).orElseThrow().state())
                .isEqualTo(DrillJob.State.RECOVERY_FAILED);
    }
}
