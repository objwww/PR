package com.objwww.pr.control.drill.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillLaunchPlan;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-02 命令服务：幂等发起（DU02 同键重放/异计划 409）、环境互斥（DU05/DU15）、
 * 服务端预检重执行、参数白名单服务端强制（DU04）、停止面（受理≠恢复完成、
 * 重复停止幂等 DU14、终态/恢复异常占位 409、stop 键跨作业 409）。
 */
class DrillJobServiceTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final List<String> ENVS = List.of("arena-195");

    // ------------------------------------------------------------------ 假件

    /** 内存仓储：模拟 V86 两枚唯一约束（幂等键/活动占位），撞约束抛 DuplicateKey */
    private static final class FakeJobs implements DrillJobRepository {
        final Map<UUID, DrillJob> byId = new LinkedHashMap<>();

        private Optional<DrillJob> byKey(String key) {
            return byId.values().stream()
                    .filter(j -> j.idempotencyKey().equals(key)).findFirst();
        }

        @Override
        public void insert(DrillJob job) {
            if (byKey(job.idempotencyKey()).isPresent()) {
                throw new DuplicateKeyException("uq_drill_job_idem");
            }
            if (findActiveOccupant(job.targetEnv(), null).isPresent()) {
                throw new DuplicateKeyException("uq_drill_job_active_env");
            }
            byId.put(job.id(), job);
        }

        @Override
        public Optional<DrillJob> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DrillJob> findByIdempotencyKey(String idempotencyKey) {
            return byKey(idempotencyKey);
        }

        @Override
        public Optional<DrillJob> findByStopKey(String stopIdempotencyKey) {
            return byId.values().stream()
                    .filter(j -> stopIdempotencyKey.equals(j.stopIdempotencyKey()))
                    .findFirst();
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
            return byId.values().stream()
                    .filter(j -> state == null || j.state().name().equals(state))
                    .sorted(Comparator.comparing(DrillJob::createdAt).reversed()
                            .thenComparing(DrillJob::id, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countActive() {
            return byId.values().stream()
                    .filter(j -> j.state().holdsEnvPlaceholder()
                            && j.state() != DrillJob.State.RECOVERY_FAILED)
                    .count();
        }

        @Override
        public long countRecoveryFailed() {
            return byId.values().stream()
                    .filter(j -> j.state() == DrillJob.State.RECOVERY_FAILED).count();
        }

        @Override
        public boolean requestStop(UUID id, String stopIdempotencyKey,
                                   Instant stopRequestedAt) {
            DrillJob job = byId.get(id);
            if (job == null || job.stopRequestedAt() != null
                    || job.state().isTerminal()
                    || job.state() == DrillJob.State.RECOVERY_FAILED) {
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
            throw new UnsupportedOperationException("worker 面");
        }

        @Override
        public boolean advance(UUID id, long expectedRevision, DrillJob.State from,
                               DrillJob.State to, Instant updatedAt) {
            throw new UnsupportedOperationException("worker 面");
        }

        @Override
        public boolean finalize(UUID id, long expectedRevision, DrillJob.State from,
                                DrillJob.State to, String terminalReason, String outcome,
                                Instant closedAt, Instant updatedAt) {
            throw new UnsupportedOperationException("worker 面");
        }

        @Override
        public List<DrillJob> findOrphanedClaims(Instant claimedBefore) {
            throw new UnsupportedOperationException("worker 面");
        }

        @Override
        public boolean requeue(UUID id, long expectedRevision, Instant updatedAt) {
            throw new UnsupportedOperationException("worker 面");
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
            return stored.stream()
                    .filter(e -> e.drillId().equals(drillId))
                    .filter(e -> e.seq() != null && e.seq() > afterSeq)
                    .sorted(Comparator.comparing(DrillEvent::seq))
                    .limit(limit)
                    .toList();
        }
    }

    /** ready 可控的测试目录（真实目录本批全 ready=false——ACCEPTED 面用 ready 夹具） */
    private static DrillTemplateCatalog catalog(boolean ready) {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: T1
                    name: 测试场景
                    scenario_type: 测试类型
                    fault_source: 测试靶场
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    target: order-arena
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
                      ready: %s
                      reason: %s
                """.formatted(ready, ready ? "" : "注入接线未交付（DR-03/DR-04）"));
    }

    private FakeJobs jobs;
    private FakeEvents events;
    private DrillJobService readyService;
    private DrillJobService unreadyService;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        events = new FakeEvents();
        ObjectMapper mapper = new ObjectMapper();
        readyService = new DrillJobService(jobs, events, catalog(true), mapper, ENVS);
        unreadyService = new DrillJobService(jobs, events, catalog(false), mapper, ENVS);
    }

    private static DrillLaunchPlan plan() {
        return new DrillLaunchPlan("T1", "arena-195", null, null, null);
    }

    // ------------------------------------------------------------------ 发起

    @Test
    @DisplayName("发起受理：QUEUED 落库、参数冻结（含后端计算 TTL）、actor=认证主体")
    void createAccepted() {
        DrillJobService.CreateResult result =
                readyService.create(plan(), "key-1", "operator");
        assertThat(result.status()).isEqualTo(DrillJobService.CreateStatus.ACCEPTED);
        DrillJob stored = jobs.findById(result.drillId()).orElseThrow();
        assertThat(stored.state()).isEqualTo(DrillJob.State.QUEUED);
        assertThat(stored.operator()).isEqualTo("operator");
        assertThat(stored.outcome()).isNull();
        assertThat(stored.paramsJson()).contains("\"ttlSeconds\":1260")
                .contains("\"trafficScale\":\"RECIPE\"")
                .contains("\"durationSeconds\":600");
    }

    @Test
    @DisplayName("DU02 幂等：同键同计划重放返回原作业；同键异计划 409 零新作业")
    void idempotentReplay() {
        DrillJobService.CreateResult first =
                readyService.create(plan(), "key-1", "operator");
        DrillJobService.CreateResult replay =
                readyService.create(plan(), "key-1", "operator");
        assertThat(replay.status()).isEqualTo(DrillJobService.CreateStatus.REPLAYED);
        assertThat(replay.drillId()).isEqualTo(first.drillId());

        DrillLaunchPlan changed = new DrillLaunchPlan("T1", "arena-195", 300, null, null);
        DrillJobService.CreateResult conflict =
                readyService.create(changed, "key-1", "operator");
        assertThat(conflict.status()).isEqualTo(DrillJobService.CreateStatus.CONFLICT_KEY);
        assertThat(conflict.drillId()).isEqualTo(first.drillId());
        assertThat(jobs.byId).hasSize(1);
    }

    @Test
    @DisplayName("DU05 环境互斥：异键同靶场活动占位 → 预检 ENV_OCCUPANCY FAIL 带占用作业身份")
    void envMutexConflict() {
        DrillJobService.CreateResult first =
                readyService.create(plan(), "key-1", "operator");
        DrillJobService.CreateResult second =
                readyService.create(plan(), "key-2", "operator");
        // 确定性面 = 服务端预检重执行拦截；并发竞态面 = CONFLICT_ENV（库占位 uq 兜底）
        assertThat(second.status())
                .isEqualTo(DrillJobService.CreateStatus.PRECHECK_FAILED);
        assertThat(second.precheck().checks())
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("ENV_OCCUPANCY");
                    assertThat(c.status()).isEqualTo(DrillPrecheck.Status.FAIL);
                    assertThat(c.detail()).contains(first.drillId().toString());
                });
        assertThat(jobs.byId).hasSize(1);
    }

    @Test
    @DisplayName("预检重执行：可执行性未交付 → PRECHECK_FAILED 带检查清单（不假启动）")
    void precheckGate() {
        DrillJobService.CreateResult result =
                unreadyService.create(plan(), "key-1", "operator");
        assertThat(result.status())
                .isEqualTo(DrillJobService.CreateStatus.PRECHECK_FAILED);
        assertThat(result.precheck().checks())
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("EXECUTION_READY");
                    assertThat(c.status()).isEqualTo(DrillPrecheck.Status.FAIL);
                });
        assertThat(jobs.byId).isEmpty();
    }

    @Test
    @DisplayName("DU04 参数白名单服务端强制：未知场景/越白名单靶场/越界时长/非法流量档 400")
    void paramWhitelistEnforced() {
        assertThatThrownBy(() -> readyService.create(
                new DrillLaunchPlan("S9", "arena-195", null, null, null), "k", "op"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("场景");
        assertThatThrownBy(() -> readyService.create(
                new DrillLaunchPlan("T1", "prod", null, null, null), "k", "op"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("白名单");
        assertThatThrownBy(() -> readyService.create(
                new DrillLaunchPlan("T1", "arena-195", 9999, null, null), "k", "op"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("区间");
        assertThatThrownBy(() -> readyService.create(
                new DrillLaunchPlan("T1", "arena-195", null, "FLOOD", null), "k", "op"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trafficScale");
        assertThatThrownBy(() -> readyService.create(plan(), "  ", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    // ------------------------------------------------------------------ 停止

    private DrillJob activeJob(DrillJob.State state, String key) {
        DrillJob job = DrillJob.queued(UUID.randomUUID(), "T1", "测试场景",
                "0".repeat(64), "arena-195", "op", "{}", "1".repeat(64), key, BASE);
        if (state != DrillJob.State.QUEUED) {
            job = job.advanced(state, state == DrillJob.State.FAILED ? "x" : null,
                    null, null, BASE);
        }
        jobs.byId.put(job.id(), job);
        return job;
    }

    @Test
    @DisplayName("停止受理：注入前 CANCELLING、注入后 RECOVERING——受理≠恢复完成，落审计事件")
    void stopAccepted() {
        DrillJob queued = activeJob(DrillJob.State.QUEUED, "k1");
        DrillJobService.StopResult pre =
                readyService.stop(queued.id(), "stop-1", "operator").orElseThrow();
        assertThat(pre.status())
                .isEqualTo(DrillJobService.StopStatus.ACCEPTED_CANCELLING);
        DrillJob after = jobs.findById(queued.id()).orElseThrow();
        assertThat(after.stopRequestedAt()).isNotNull();
        assertThat(after.state()).isEqualTo(DrillJob.State.QUEUED); // 推进归 worker
        assertThat(events.stored)
                .anySatisfy(e -> assertThat(e.eventType())
                        .isEqualTo(DrillEvent.EventType.STOP_REQUESTED));

        DrillJob observing = activeJob(DrillJob.State.OBSERVING, "k2");
        DrillJobService.StopResult post =
                readyService.stop(observing.id(), "stop-2", "operator").orElseThrow();
        assertThat(post.status())
                .isEqualTo(DrillJobService.StopStatus.ACCEPTED_RECOVERING);
    }

    @Test
    @DisplayName("DU14 重复停止幂等：同 stop 键 REPLAYED；异键 ALREADY_REQUESTED 零新副作用")
    void stopIdempotent() {
        DrillJob job = activeJob(DrillJob.State.OBSERVING, "k1");
        readyService.stop(job.id(), "stop-1", "operator");
        DrillJobService.StopResult replay =
                readyService.stop(job.id(), "stop-1", "operator").orElseThrow();
        assertThat(replay.status()).isEqualTo(DrillJobService.StopStatus.REPLAYED);
        DrillJobService.StopResult again =
                readyService.stop(job.id(), "stop-2", "operator").orElseThrow();
        assertThat(again.status())
                .isEqualTo(DrillJobService.StopStatus.ALREADY_REQUESTED);
        assertThat(events.stored).filteredOn(e -> e.eventType()
                == DrillEvent.EventType.STOP_REQUESTED).hasSize(1);
    }

    @Test
    @DisplayName("停止非法面：终态 409；恢复异常占位 409；stop 键跨作业 409；未知作业 404")
    void stopConflicts() {
        DrillJob failed = activeJob(DrillJob.State.FAILED, "k1");
        assertThat(readyService.stop(failed.id(), "s-1", "op").orElseThrow().status())
                .isEqualTo(DrillJobService.StopStatus.CONFLICT_TERMINAL);

        DrillJob recoveryFailed = activeJob(DrillJob.State.QUEUED, "k2")
                .advanced(DrillJob.State.PRECHECK, null, null, null, BASE)
                .advanced(DrillJob.State.RECOVERY_FAILED, "worker_lost", null, null, BASE);
        jobs.byId.put(recoveryFailed.id(), recoveryFailed);
        assertThat(readyService.stop(recoveryFailed.id(), "s-2", "op")
                .orElseThrow().status())
                .isEqualTo(DrillJobService.StopStatus.CONFLICT_RECOVERY_FAILED);

        DrillJob a = activeJob(DrillJob.State.OBSERVING, "k3");
        DrillJob b = activeJob(DrillJob.State.OBSERVING, "k4");
        jobs.byId.put(a.id(), a); // k3/k4 同 env 不同行（夹具绕过占位约束）
        readyService.stop(a.id(), "shared-stop-key", "op");
        assertThat(readyService.stop(b.id(), "shared-stop-key", "op")
                .orElseThrow().status())
                .isEqualTo(DrillJobService.StopStatus.CONFLICT_KEY);

        assertThat(readyService.stop(UUID.randomUUID(), "s-9", "op")).isEmpty();
    }

    // ------------------------------------------------------------------ 查询投影

    @Test
    @DisplayName("列表投影：前端契约字段 + summary 真计数（active/recoveryFailed）")
    void listProjection() {
        activeJob(DrillJob.State.QUEUED, "k1");
        DrillJobService.ListResponse resp = readyService.list(null, null, 50);
        assertThat(resp.items()).hasSize(1);
        DrillJobService.ListItem item = resp.items().getFirst();
        assertThat(item.scenarioId()).isEqualTo("T1");
        assertThat(item.scenarioName()).isEqualTo("测试场景");
        assertThat(item.targetEnv()).isEqualTo("arena-195");
        assertThat(item.operator()).isEqualTo("op");
        assertThat(item.state()).isEqualTo("QUEUED");
        assertThat(item.outcome()).isNull();
        assertThat(resp.summary().active()).isEqualTo(1);
        assertThat(resp.summary().recoveryFailed()).isZero();
    }

    @Test
    @DisplayName("详情投影：八阶段时间线 + 冻结参数审计 + 关联面 null 如实（尚未关联）")
    void detailProjection() {
        DrillJob job = activeJob(DrillJob.State.QUEUED, "k1");
        DrillJobService.DetailResponse detail =
                readyService.detail(job.id()).orElseThrow();
        assertThat(detail.timeline()).hasSize(8);
        assertThat(detail.timeline().getFirst().status())
                .isEqualTo(DrillTimeline.Status.DONE);
        assertThat(detail.related().incidentId()).isNull();
        assertThat(detail.related().runId()).isNull();
        assertThat(readyService.detail(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ 事件流

    /** 直接落带 seq 的账本行（正常路径 seq 由库 identity 生成，夹具显式赋值模拟） */
    private DrillEvent storedEvent(DrillJob job, long seq, DrillEvent.EventType type) {
        DrillEvent e = new DrillEvent(UUID.randomUUID(), job.id(), seq, type,
                null, null, "op", "{\"note\":true}", BASE);
        events.stored.add(e);
        return e;
    }

    @Test
    @DisplayName("事件流投影：游标=seq 严格大于续页、满页才给 nextCursor、payload 透传解析值")
    void eventsCursorPaging() {
        DrillJob job = activeJob(DrillJob.State.QUEUED, "k1");
        storedEvent(job, 1, DrillEvent.EventType.PHASE_TRANSITION);
        storedEvent(job, 2, DrillEvent.EventType.PRECHECK_RESULT);
        storedEvent(job, 3, DrillEvent.EventType.WORKER_NOTE);

        DrillJobService.EventsResponse page1 =
                readyService.events(job.id(), 0, 2).orElseThrow();
        assertThat(page1.items()).extracting(DrillJobService.EventItem::seq)
                .containsExactly(1L, 2L);
        assertThat(page1.nextCursor()).isEqualTo(2L);
        assertThat(page1.items().getFirst().eventType()).isEqualTo("PHASE_TRANSITION");
        assertThat(page1.items().getFirst().payload()).isInstanceOf(Map.class);

        DrillJobService.EventsResponse page2 =
                readyService.events(job.id(), page1.nextCursor(), 2).orElseThrow();
        assertThat(page2.items()).extracting(DrillJobService.EventItem::seq)
                .containsExactly(3L);
        assertThat(page2.nextCursor()).isNull(); // 未满页 = 没有更多

        // 归属隔离：他作业事件不混入（k3/k4 同 env 不同行，夹具绕过占位约束）
        DrillJob other = activeJob(DrillJob.State.QUEUED, "k9");
        storedEvent(other, 4, DrillEvent.EventType.WORKER_NOTE);
        assertThat(readyService.events(job.id(), 0, 50).orElseThrow().items())
                .extracting(DrillJobService.EventItem::seq)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("事件流 404 面：未知作业 empty（与 detail 同语义，不返回空账本冒充存在）")
    void eventsUnknownDrill() {
        assertThat(readyService.events(UUID.randomUUID(), 0, 50)).isEmpty();
    }
}
