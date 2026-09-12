package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import com.objwww.pr.control.eval.application.AlertProbe;
import com.objwww.pr.control.eval.application.ArenaChaosScenarioDriver;
import com.objwww.pr.control.eval.application.ArenaTrafficClient;
import com.objwww.pr.control.eval.application.ChaosAdminClient;
import com.objwww.pr.control.eval.application.FlagdScenarioDriver;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.shared.Digest;
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
 * DR-03 worker 集成面：DrillWorker 装配 {@link CompositeDrillInjection} 后，
 * arena 作业从 QUEUED 一路驱动到 OBSERVING——相位链完整、激活回执（含每作业
 * 固定有效实例 id）落 WORKER_NOTE 事件账，作业保持活动占位（停止/恢复推进归
 * DR-04 面）。假件全内存（DrillWorkerTest 同习语），settle 等待为生产固定 3s。
 */
class DrillWorkerInjectionTest {

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
            throw new UnsupportedOperationException();
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

    private static final class FakeChaosAdminClient implements ChaosAdminClient {
        int onCalls;

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            onCalls++;
            return new Activation("sess-fixed",
                    String.valueOf(body.get("scenarioId")), 7L, "fp-fixed");
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionStatus status(String scenarioId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAlertProbe implements AlertProbe {
        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rule=" + alertname);
        }
    }

    private static final class FakeTraffic implements ArenaTrafficClient {
        final List<String> orders = new ArrayList<>();

        @Override
        public void createOrder(String intentId, String correlationId, String sku) {
            orders.add(intentId + "/" + correlationId);
        }
    }

    /** flagd 面桩（本测试不路由到 flagd；被调即失败暴露串线） */
    private static final class NoopFlagAdminClient
            implements FlagdScenarioDriver.FlagAdminClient {
        @Override
        public String setDefaultVariant(String flag, String variant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.objwww.pr.control.drill.domain.model.FlagdState
                readDefaultVariant(String flag) {
            throw new UnsupportedOperationException();
        }
    }

    private static final String REGISTRY_YAML = """
            registry_version: 2
            schema_version: 1
            scenarios:
              - scenario_id: S3
                name: F1 幂等失效
                driver: ArenaChaosScenarioDriver
                chaos_family: F1
                target: order-arena
                expected_root_cause: {component: order-arena,
                                      fault_type: IDEMPOTENCY,
                                      reason_code: IDEM_X}
                expected_symptom_codes: [ArenaDuplicateOrders]
                timing: {preheat_seconds: 1, hold_seconds: 2,
                         max_firing_wait_seconds: 3,
                         max_resolved_wait_seconds: 4,
                         cleanup_timeout_seconds: 5}
            """;

    // ------------------------------------------------------------------ 装配

    private FakeJobs jobs;
    private FakeEvents events;
    private FixedClock clock;
    private FakeChaosAdminClient chaos;
    private FakeTraffic traffic;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
        clock = new FixedClock();
        chaos = new FakeChaosAdminClient();
        traffic = new FakeTraffic();
    }

    private DrillWorker worker() {
        DrillTemplateCatalog catalog = DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: S3
                    name: F1 幂等失效
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    target: order-arena
                    symptom_codes: [ArenaDuplicateOrders]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
        GoldenScenarioRegistry registry = GoldenScenarioRegistry.load(REGISTRY_YAML);
        FakeAlertProbe probe = new FakeAlertProbe();
        CompositeDrillInjection injection = new CompositeDrillInjection(catalog,
                registry, ENVS,
                new ArenaChaosDrillInjection(chaos, probe, traffic, "ds-drill"),
                new FlagdDrillInjection(new FlagdScenarioDriver(
                        new NoopFlagAdminClient(), probe)));
        return new DrillWorker(jobs, events, catalog, injection, clock, ENVS,
                "drill-worker-1", 5, 900);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("真实注入接线：QUEUED→PRECHECK→INJECTING→OBSERVING，"
            + "回执（每作业固定有效实例 id）落 WORKER_NOTE，活动占位保持")
    void arenaJobDrivenToObservingWithReceipt() {
        DrillJob job = DrillJob.queued(UUID.randomUUID(), "S3", "F1 幂等失效",
                "0".repeat(64), "arena-195", "operator", "{}", "1".repeat(64),
                "key-" + UUID.randomUUID(), BASE);
        jobs.insert(job);

        boolean worked = worker().tick();

        assertThat(worked).isTrue();
        DrillJob after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(DrillJob.State.OBSERVING);
        assertThat(after.terminalReason()).isNull();
        assertThat(after.outcome()).isNull(); // CLOSED 才落 outcome
        assertThat(after.state().holdsEnvPlaceholder()).isTrue();
        List<String> transitions = events.stored.stream()
                .filter(e -> e.eventType() == DrillEvent.EventType.PHASE_TRANSITION)
                .map(e -> e.fromState() + "→" + e.toState()).toList();
        assertThat(transitions).containsExactly("QUEUED→PRECHECK",
                "PRECHECK→INJECTING", "INJECTING→OBSERVING");
        String fixedId = ArenaChaosScenarioDriver.effectiveScenarioId(
                GoldenScenarioRegistry.load(REGISTRY_YAML).byScenarioId("S3"), 1,
                ArenaChaosDrillInjection.runTagFor(job));
        assertThat(events.stored).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(DrillEvent.EventType.WORKER_NOTE);
            assertThat(e.payloadJson()).contains(fixedId)
                    .contains("\"generation\":7")
                    .contains(job.id().toString());
        });
        assertThat(chaos.onCalls).isEqualTo(1);
        assertThat(traffic.orders).hasSize(3);
    }
}
