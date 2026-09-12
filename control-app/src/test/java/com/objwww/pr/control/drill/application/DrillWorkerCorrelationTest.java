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
 * DR-06 drill→告警/调查关联回填（§7.5）：OBSERVING 相位按「注入时间窗 + 场景主症状
 * 标签」匹配——匹配到才回填（currentRcaRunId 存在才带 runId）并落 WORKER_NOTE；
 * 无匹配/窗口已过/缺迁移事件保持 null（前端「尚未关联」）；重复回填幂等。
 */
class DrillWorkerCorrelationTest {

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
            DrillJob job = byId.get(id);
            if (job == null || job.revision() != expectedRevision
                    || job.relatedIncidentId() != null) {
                return false;
            }
            byId.put(id, new DrillJob(job.id(), job.scenarioId(), job.scenarioName(),
                    job.templateDigest(), job.targetEnv(), job.operator(), job.state(),
                    job.outcome(), job.terminalReason(), job.paramsJson(),
                    job.payloadHash(), job.idempotencyKey(), job.stopIdempotencyKey(),
                    job.stopRequestedAt(), job.workerId(), job.claimedAt(),
                    job.revision() + 1, incidentId, runId, job.createdAt(),
                    updatedAt, job.closedAt()));
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

        @Override
        public List<DrillEvent> listByDrillAfter(UUID drillId, long afterSeq, int limit) {
            throw new UnsupportedOperationException();
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

    /** 关联端口桩：记录调用参数，返回预设结果 */
    private static final class StubCorrelation implements DrillCorrelationPort {
        Correlation result;
        final List<String> calledWith = new ArrayList<>();

        @Override
        public Optional<Correlation> correlate(String primaryAlertname, Instant injectedAt,
                                               Instant windowEnd) {
            calledWith.add(primaryAlertname + "|" + injectedAt + "|" + windowEnd);
            return Optional.ofNullable(result);
        }
    }

    private FakeJobs jobs;
    private FakeEvents events;
    private FixedClock clock;
    private StubCorrelation correlation;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
        clock = new FixedClock();
        correlation = new StubCorrelation();
    }

    private DrillWorker worker() {
        return new DrillWorker(jobs, events, catalog(),
                new DrillInjectionPort.NotImplemented(), clock, ENVS, "drill-worker-1",
                5, 900, correlation, null);
    }

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
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
    }

    /** 摆一个 OBSERVING 作业 + 注入迁移事件（注入时刻 = BASE） */
    private DrillJob observingJob() {
        DrillJob job = new DrillJob(UUID.randomUUID(), "T1", "测试场景", "0".repeat(64),
                "arena-195", "operator", DrillJob.State.OBSERVING, null, null, "{}",
                "1".repeat(64), "key-" + UUID.randomUUID(), null, null,
                "drill-worker-1", BASE, 0, null, null, BASE, BASE, null);
        jobs.insert(job);
        events.insert(DrillEvent.phaseTransition(job.id(), DrillJob.State.INJECTING,
                DrillJob.State.OBSERVING, "drill-worker-1", "{}", BASE));
        return job;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("匹配成功：回填 incident + run（currentRcaRunId 存在），落 WORKER_NOTE")
    void linksIncidentAndRunOnMatch() {
        DrillJob job = observingJob();
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        correlation.result = new DrillCorrelationPort.Correlation(incidentId, runId);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.relatedIncidentId()).isEqualTo(incidentId);
        assertThat(after.relatedRunId()).isEqualTo(runId);
        assertThat(correlation.calledWith).containsExactly(
                "checkout|" + BASE + "|" + BASE.plusSeconds(300));
        assertThat(events.stored).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(DrillEvent.EventType.WORKER_NOTE);
            assertThat(e.payloadJson()).contains(incidentId.toString())
                    .contains(runId.toString())
                    .contains("primary-symptom+injection-window");
        });
    }

    @Test
    @DisplayName("incident 无 currentRcaRunId → 只回填 incident，runId 保持 null")
    void linksIncidentOnlyWhenNoRun() {
        DrillJob job = observingJob();
        UUID incidentId = UUID.randomUUID();
        correlation.result = new DrillCorrelationPort.Correlation(incidentId, null);
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.relatedIncidentId()).isEqualTo(incidentId);
        assertThat(after.relatedRunId()).isNull();
    }

    @Test
    @DisplayName("无匹配 → 保持 null（尚未关联），不落关联事件、不瞎关联")
    void noMatchKeepsNull() {
        DrillJob job = observingJob();
        worker().tick();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.relatedIncidentId()).isNull();
        assertThat(after.relatedRunId()).isNull();
        assertThat(events.stored).noneMatch(e ->
                e.payloadJson() != null && e.payloadJson().contains("correlation"));
    }

    @Test
    @DisplayName("注入窗口已过（now > injectedAt + maxFiringWait）→ 不再匹配，保持 null")
    void windowExpiredSkipsMatching() {
        DrillJob job = observingJob();
        correlation.result = new DrillCorrelationPort.Correlation(UUID.randomUUID(), null);
        clock.now = BASE.plusSeconds(301);
        worker().tick();
        assertThat(correlation.calledWith).isEmpty(); // 窗口外不查不猜
        assertThat(jobs.findById(job.id()).orElseThrow().relatedIncidentId()).isNull();
    }

    @Test
    @DisplayName("缺 INJECTING→OBSERVING 迁移事件（注入时刻不可考）→ 不匹配")
    void missingTransitionEventSkips() {
        DrillJob job = observingJob();
        events.stored.clear(); // 抹掉迁移事件
        correlation.result = new DrillCorrelationPort.Correlation(UUID.randomUUID(), null);
        worker().tick();
        assertThat(correlation.calledWith).isEmpty();
        assertThat(jobs.findById(job.id()).orElseThrow().relatedIncidentId()).isNull();
    }

    @Test
    @DisplayName("重复回填幂等：第二拍 related 已非空即跳过，不再调用不重复落事件")
    void repeatedBackfillIsIdempotent() {
        DrillJob job = observingJob();
        correlation.result = new DrillCorrelationPort.Correlation(UUID.randomUUID(),
                UUID.randomUUID());
        worker().tick();
        worker().tick();
        assertThat(correlation.calledWith).hasSize(1);
        assertThat(events.stored.stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.WORKER_NOTE
                        && e.payloadJson().contains("correlation"))
                .count()).isEqualTo(1);
    }
}
