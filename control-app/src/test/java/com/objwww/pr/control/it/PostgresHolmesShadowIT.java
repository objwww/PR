package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.HolmesShadowSampler;
import com.objwww.pr.control.alert.application.HolmesShadowWorker;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository;
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresHolmesShadowWorkRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository.ComparisonRow;
import com.objwww.pr.control.infrastructure.persistence.PostgresEngineComparisonRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-05 Holmes shadow 持久工作面真 PG 集成（V34；命名 *IT 本机无 docker 自动跳过，
 * 195 真跑补证据）：
 * <ul>
 *   <li>SKIP LOCKED 并发认领：20 路并发 claimBatch 每行恰被一 worker 认领（attempts=1，
 *       无双领）；</li>
 *   <li>租约过期回收 + 有界重试：未过期不可重领；过期回收 attempts/epoch 双 +1；
 *       attempts ≥ max → EXHAUSTED 永不再入批；</li>
 *   <li>收口 CAS epoch 栅栏：过期持有者 complete/markFailed 全 0 行，现持有者恰一胜出；</li>
 *   <li>授权面真库兜底（V34 差异化授权）：control_app 可 UPDATE（租约语义）但
 *       DELETE 拒（42501，工作历史不可抹）；</li>
 *   <li>Worker 影子锚真 FK 链：run(RUNNING→SUCCEEDED 终态自收)/task(DONE)/
 *       attempt(STARTED→SUCCEEDED) 全部落真表，V32 对照行恰一次落账 + 校准行幂等入队。</li>
 * </ul>
 */
class PostgresHolmesShadowIT extends PostgresITBase {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private PostgresRcaRunRepository runs;
    private PostgresRcaTaskRepository tasks;
    private PostgresRcaAttemptRepository attempts;
    private PostgresIncidentRepository incidents;
    private PostgresHolmesShadowWorkRepository works;
    private PostgresEngineComparisonRepository comparisons;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        runs = new PostgresRcaRunRepository(controlJdbc);
        tasks = new PostgresRcaTaskRepository(controlJdbc);
        attempts = new PostgresRcaAttemptRepository(controlJdbc);
        incidents = new PostgresIncidentRepository(controlJdbc);
        works = new PostgresHolmesShadowWorkRepository(controlJdbc);
        comparisons = new PostgresEngineComparisonRepository(controlJdbc);
    }

    @Test
    void enqueueIsIdempotentOnDeterministicShadowKey() {
        UUID nativeRunId = seedNativeRun("idem");
        String key = "holmes-shadow:" + nativeRunId;
        ShadowWorkRow row = ShadowWorkRow.forEnqueue(key, "COMPARISON", nativeRunId,
                seedIncidentOnly("idem"), 0, Digest.sha256Of("snap").hex(), 3);
        assertThat(works.enqueue(row)).isTrue();
        assertThat(works.enqueue(row)).as("撞确定性 key = 幂等败者").isFalse();
        assertThat(count("holmes_shadow_work")).isEqualTo(1);
        assertThat(works.findByShadowKey(key)).isPresent();
        assertThat(works.findByShadowKey("holmes-shadow:absent")).isEmpty();
    }

    @Test
    void twentyWayConcurrentClaimClaimsEachRowExactlyOnce() throws Exception {
        for (int i = 0; i < 10; i++) {
            works.enqueue(ShadowWorkRow.forEnqueue("holmes-shadow:row-" + i, "COMPARISON",
                    seedNativeRun("claim-" + i), seedIncidentOnly("claim-" + i), 0,
                    Digest.sha256Of("s" + i).hex(), 3));
        }
        Instant now = Instant.now();

        record Claimed(String owner, long id) {
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Claimed> all = new ArrayList<>();
        try {
            List<Future<List<Claimed>>> futures = IntStream.range(0, 20)
                    .mapToObj(t -> pool.submit(() -> works
                            .claimBatch("worker-" + t, now, LEASE, 3).stream()
                            .map(r -> new Claimed("worker-" + t, r.id())).toList()))
                    .toList();
            for (Future<List<Claimed>> future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(all).as("10 行全被领走").hasSize(10);
        Set<Long> distinct = new HashSet<>();
        all.forEach(c -> distinct.add(c.id()));
        assertThat(distinct).as("SKIP LOCKED 下无双领").hasSize(10);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM holmes_shadow_work
                        WHERE state = 'LEASED' AND attempts = 1 AND lease_epoch = 1
                        """).query(Long.class).single())
                .as("认领即租约续身 + attempts/epoch 双 +1").isEqualTo(10);
    }

    @Test
    void leaseRecoveryAndBoundedRetryReachExhausted() {
        ShadowWorkRow row = enqueueOne("lease");
        Instant now = Instant.now();

        // 首领：attempts=1
        List<ShadowWorkRow> first = works.claimBatch("w1", now, LEASE, 5);
        assertThat(first).hasSize(1);
        // 未过期不可重领
        assertThat(works.claimBatch("w2", now.plusSeconds(1), LEASE, 5)).isEmpty();
        // 过期回收：attempts/epoch 双 +1
        List<ShadowWorkRow> reclaimed = works.claimBatch("w2", now.plus(LEASE).plusSeconds(1),
                LEASE, 5);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).attempts()).isEqualTo(2);
        assertThat(reclaimed.get(0).leaseEpoch()).isEqualTo(2);
        assertThat(reclaimed.get(0).leaseOwner()).isEqualTo("w2");
        // FAILED 可再领（第三次），attempts 耗尽 → EXHAUSTED，永不再入批
        assertThat(works.markFailed(reclaimed.get(0).id(), "w2", 2, "boom", Instant.now()))
                .isEqualTo(1);
        List<ShadowWorkRow> third = works.claimBatch("w3", Instant.now(), LEASE, 5);
        assertThat(third).hasSize(1);
        assertThat(works.markFailed(third.get(0).id(), "w3", 3, "boom", Instant.now()))
                .isEqualTo(1);
        assertThat(adminJdbc.sql("""
                        SELECT state FROM holmes_shadow_work WHERE id = :id
                        """).param("id", third.get(0).id()).query(String.class).single())
                .isEqualTo("EXHAUSTED");
        assertThat(works.claimBatch("w4", Instant.now(), LEASE, 5)).isEmpty();
    }

    @Test
    void completeAndMarkFailedAreEpochFencedCas() {
        ShadowWorkRow row = enqueueOne("cas");
        Instant now = Instant.now();
        ShadowWorkRow original = works.claimBatch("w1", now, LEASE, 5).get(0);
        // 租约过期 → 他者回收
        ShadowWorkRow thief = works.claimBatch("w2", now.plus(LEASE).plusSeconds(1), LEASE, 5)
                .get(0);
        // 原持有者（旧 epoch）收口全 0 行
        assertThat(works.complete(original.id(), original.leaseOwner(),
                original.leaseEpoch(), 30, Instant.now())).isZero();
        assertThat(works.markFailed(original.id(), original.leaseOwner(),
                original.leaseEpoch(), "stale", Instant.now())).isZero();
        // 现持有者恰一胜出
        assertThat(works.complete(thief.id(), thief.leaseOwner(), thief.leaseEpoch(),
                30, Instant.now())).isEqualTo(1);
        assertThat(works.findByShadowKey(row.shadowKey()).orElseThrow().state())
                .isEqualTo("SUCCEEDED");
        // 终态行不再可领
        assertThat(works.claimBatch("w3", Instant.now(), LEASE, 5)).isEmpty();
    }

    @Test
    void controlAppCanUpdateLeaseColumnsButNeverDelete() {
        ShadowWorkRow row = enqueueOne("grant");
        // V34 差异化授权（有意）：UPDATE 是租约语义的一部分
        controlJdbc.sql("UPDATE holmes_shadow_work SET last_error = 'lease-note' WHERE id = :id")
                .param("id", row.id()).update();
        assertThat(works.findByShadowKey(row.shadowKey()).orElseThrow().lastError())
                .isEqualTo("lease-note");
        // DELETE 仍拒（42501，工作历史不可抹）
        assertThatThrownBy(() -> controlJdbc.sql("DELETE FROM holmes_shadow_work").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(count("holmes_shadow_work")).isEqualTo(1);
    }

    @Test
    void workerShadowChainLandsComparisonWithRealFkAnchorsAndQueuesCalibration() {
        UUID incidentId = seedIncidentOnly("worker");
        Digest snapshot = Digest.sha256Of("it-snapshot");
        UUID nativeRunId = UUID.randomUUID();
        runs.insertRouted(new RcaRun(nativeRunId, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), null), new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle"), "svc-worker", 7, "BUCKETED_NATIVE"));

        works.enqueue(ShadowWorkRow.forEnqueue("holmes-shadow:" + nativeRunId, "COMPARISON",
                nativeRunId, incidentId, 0, snapshot.hex(), 3));
        ShadowWorkRow claimed = works.claimBatch("shadow-owner", Instant.now(), LEASE, 5)
                .get(0);

        AlertClock clock = Instant::now;
        ClaimStore claimStore = new PostgresClaimStore(controlJdbc, controlTx,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender(
                        controlJdbc, controlTx, controlTx));
        EngineComparisonRecorder recorder = new EngineComparisonRecorder(
                noBundles(), runs, new com.objwww.pr.control.infrastructure.persistence
                        .PostgresRcaReportRepository(controlJdbc), claimStore, comparisons,
                AlertMetrics.NOOP);
        StubExecutor executor = new StubExecutor();
        HolmesShadowWorker worker = new HolmesShadowWorker(works, runs, incidents, tasks,
                attempts, executor, comparisons, recorder, SlaPolicy.defaults(), clock,
                AlertMetrics.NOOP, "shadow-owner");

        HolmesShadowWorker.Outcome outcome = worker.process(claimed);

        assertThat(outcome).isEqualTo(HolmesShadowWorker.Outcome.COMPARISON_LANDED);
        // 影子锚三条真行：run SUCCEEDED 终态自收 / task DONE / attempt SUCCEEDED
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM rca_run WHERE state = 'SUCCEEDED'
                          AND finished_at IS NOT NULL AND id <> :native
                        """).param("native", nativeRunId).query(Long.class).single())
                .isEqualTo(1);
        UUID shadowRunId = adminJdbc.sql("""
                        SELECT id FROM rca_run WHERE state = 'SUCCEEDED' AND id <> :native
                        """).param("native", nativeRunId).query(UUID.class).single();
        assertThat(adminJdbc.sql("""
                        SELECT t.state FROM rca_task t WHERE t.run_id = :run
                        """).param("run", shadowRunId).query(String.class).single())
                .isEqualTo(RcaTaskState.DONE.name());
        assertThat(adminJdbc.sql("""
                        SELECT a.status FROM rca_attempt a JOIN rca_task t ON t.id = a.task_id
                        WHERE t.run_id = :run
                        """).param("run", shadowRunId).query(String.class).single())
                .isEqualTo(RcaAttemptStatus.SUCCEEDED.name());
        // 工作行收口：SUCCEEDED + tokens；V32 对照行恰一次（holmes 预铸结论带根因三元组）
        assertThat(works.findByShadowKey("holmes-shadow:" + nativeRunId)
                .orElseThrow().state()).isEqualTo("SUCCEEDED");
        List<ComparisonRow> landed = comparisons.findByNativeRunId(nativeRunId);
        assertThat(landed).hasSize(1);
        assertThat(landed.get(0).shadowExecRef()).isEqualTo(HolmesShadowWorker.SHADOW_EXEC_REF);
        assertThat(landed.get(0).holmesOutcome()).containsEntry("engine", "HOLMES");
        assertThat(String.valueOf(landed.get(0).holmesOutcome().get("root_cause")))
                .contains("checkout");
        // 校准工作幂等入队（kind CALIBRATION）
        Optional<ShadowWorkRow> calib = works.findByShadowKey(
                "holmes-calib:" + nativeRunId);
        assertThat(calib).isPresent();
        assertThat(calib.orElseThrow().kind()).isEqualTo("CALIBRATION");
        assertThat(executor.executedRunIds).containsExactly(shadowRunId);
    }

    // ------------------------------------------------------------------ 种子与桩

    /** 可编程执行器：只回结果不触网（真 PG 面验证的是锚链与落账，触网由 195 E2E 补） */
    private static final class StubExecutor implements RcaTaskExecutor {
        final List<UUID> executedRunIds = new ArrayList<>();
        ExecutionResult next = ExecutionResult.success(new AttemptArtifact(2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), PACKAGE, "raw",
                typedPackage(), List.of(), Digest.sha256Of("raw"),
                Digest.sha256Of(PACKAGE), "glm-5", 10, 20, 30, false, fingerprint()));

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                RcaAttempt attempt, Runnable heartbeat) {
            executedRunIds.add(run.id());
            return next;
        }

        /** V23 ck_rca_attempt_fingerprint_keys：非空指纹必须五顶层键全带（生产形态镜像） */
        private static Map<String, Object> fingerprint() {
            return new com.objwww.pr.control.eval.domain.model.SamplingFingerprint(
                    new com.objwww.pr.control.eval.domain.model.SamplingFingerprint.Sampling(
                            0.2, 0.9, 2048, null),
                    new com.objwww.pr.control.eval.domain.model.SamplingFingerprint.Sampling(
                            0.2, 0.9, 2048, null),
                    "litellm:glm-5@dashscope", "glm-5", 0).toMap();
        }

        private static EvidencePackageV2 typedPackage() {
            try {
                return EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(PACKAGE)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final String PACKAGE = """
            {"schema_version":2,"summary":"s",
             "root_cause":{"component":"checkout","fault_type":"dep_failure",
                           "reason_code":"CONN_TIMEOUT"},
             "claims":[],"evidence":["e1"],"impact":"i","remediation":"r","references":[]}
            """;

    private static ConfigBundleRepository noBundles() {
        return new ConfigBundleRepository() {
            @Override
            public long nextRevision() {
                return 1;
            }

            @Override
            public boolean insert(com.objwww.pr.control.release.domain.model.ConfigBundle b) {
                return true;
            }

            @Override
            public Optional<com.objwww.pr.control.release.domain.model.ConfigBundle> findByDigest(
                    Digest digest) {
                return Optional.empty();
            }

            @Override
            public Optional<Digest> activeDigest() {
                return Optional.empty();
            }

            @Override
            public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
                return Optional.empty();
            }

            @Override
            public java.util.List<ConfigBundleRepository.BundleSummary> listRecent(int limit) {
                return java.util.List.of();
            }

            @Override
            public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                    String by, Instant at) {
                return true;
            }
        };
    }

    private ShadowWorkRow enqueueOne(String tag) {
        works.enqueue(ShadowWorkRow.forEnqueue("holmes-shadow:" + tag, "COMPARISON",
                seedNativeRun(tag), seedIncidentOnly(tag), 0, Digest.sha256Of(tag).hex(), 3));
        return works.findByShadowKey("holmes-shadow:" + tag).orElseThrow();
    }

    private UUID seedIncidentOnly(String tag) {
        UUID incidentId = UUID.randomUUID();
        controlJdbc.sql("""
                        INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                            first_seen_at, last_event_at, created_at, updated_at)
                        VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                        """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=shadow-" + tag + "-" + incidentId)
                .update();
        return incidentId;
    }

    /**
     * 种子终态 NATIVE run（V34 native_run_id 有真 FK——工作行引用的 run 必须在案；
     * 终态 SUCCEEDED = 生产抽样前提形态，活跃位约束零牵连）。
     */
    private UUID seedNativeRun(String tag) {
        UUID runId = UUID.randomUUID();
        runs.insertRouted(new RcaRun(runId, seedIncidentOnly(tag), 0, RunTrigger.INITIAL,
                        RcaRunState.SUCCEEDED, Digest.sha256Of("run-" + tag), Instant.now(),
                        Instant.now(), Instant.now(), Instant.now(), null),
                new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of("bundle-" + tag),
                        "svc-" + tag, 7, "BUCKETED_NATIVE"));
        return runId;
    }
}
