package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
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
import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HolmesShadowWorker 单测（M6-05 执行面）：零发布纪律（run/task/attempt 影子锚 +
 * 零 report/publication/outbox/winner）、恰一次对照落账、校准底噪落 noise_baseline、
 * 有界重试/租约 CAS 败者作废/锚缺失与 incident 忙的诚实失败。
 */
class HolmesShadowWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final String OWNER = "shadow-owner";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HOLMES_V2_PACKAGE = """
            {"schema_version":2,"summary":"s",
             "root_cause":{"component":"checkout","fault_type":"dep_failure",
                           "reason_code":"CONN_TIMEOUT"},
             "claims":[
               {"claim_type":"dep_failure","status":"TRUE","component":"checkout",
                "fault_type":"dep_failure","symptom_codes":["p99"],"evidence_refs":["e1"]}],
             "evidence":["e1"],"impact":"i","remediation":"r","references":[]}
            """;

    private static final class MutableClock implements AlertClock {
        volatile Instant now = NOW;

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 可编程执行器：返回预置结果，记录见到的影子 run（锚面断言） */
    private static final class StubExecutor implements RcaTaskExecutor {
        ExecutionResult next;
        final List<UUID> executedRunIds = new ArrayList<>();

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                RcaAttempt attempt, Runnable heartbeat) {
            executedRunIds.add(run.id());
            return next;
        }
    }

    private static final class MemoryClaims implements ClaimStore {
        private final List<ClaimRow> rows = new ArrayList<>();

        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            return new ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return rows.stream().filter(r -> r.runId().equals(runId)).toList();
        }
    }

    private static final class MemoryComparisons implements EngineComparisonRepository {
        private final List<ComparisonRow> rows = new ArrayList<>();
        private final java.util.Set<String> keys = new java.util.HashSet<>();

        @Override
        public boolean append(ComparisonRow row) {
            if (!keys.add(row.nativeRunId() + "|" + row.comparisonKey())) {
                return false;
            }
            rows.add(row);
            return true;
        }

        @Override
        public List<ComparisonRow> findByNativeRunId(UUID nativeRunId) {
            return rows.stream().filter(r -> r.nativeRunId().equals(nativeRunId)).toList();
        }
    }

    private static final class NoBundles implements ConfigBundleRepository {
        @Override
        public long nextRevision() {
            return 1;
        }

        @Override
        public boolean insert(com.objwww.pr.control.release.domain.model.ConfigBundle bundle) {
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
    }

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final MutableClock clock = new MutableClock();
    private final StubExecutor executor = new StubExecutor();
    private final MemoryClaims claims = new MemoryClaims();
    private final MemoryComparisons comparisons = new MemoryComparisons();

    private HolmesShadowWorker worker() {
        EngineComparisonRecorder recorder = new EngineComparisonRecorder(new NoBundles(),
                stores.runs, stores.reports, claims, comparisons, AlertMetrics.NOOP);
        return new HolmesShadowWorker(stores.shadowWorks, stores.runs, stores.incidents,
                stores.tasks, stores.attempts, executor, comparisons, recorder,
                SlaPolicy.defaults(), clock, AlertMetrics.NOOP, OWNER);
    }

    private record Seed(UUID incidentId, UUID nativeRunId, Digest snapshot) {
    }

    private Seed seedNativeSucceeded() {
        UUID incidentId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId,
                "alertname=HighErrorRate|service=checkout", IncidentStatus.FIRING, 2, NOW,
                NOW, null, null, null, 0, 0, 0, null, NOW, NOW, NOW, NOW));
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, 2, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("snapshot"), NOW, NOW,
                NOW.minusSeconds(10), NOW, null);
        stores.runs.insert(run);
        stores.runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of("bundle"), "key", 7, "BUCKETED_NATIVE"));
        // native 生产结论（claims 投影）：与影子 holmes 包根因同键异 reason → result 维差异
        claims.rows.add(claimRow(run.id(), "dep_failure", ClaimStatus.TRUE, "checkout",
                "CONN_RESET"));
        return new Seed(incidentId, run.id(), run.investigationHash());
    }

    private ShadowWorkRow enqueueComparison(Seed seed) {
        stores.shadowWorks.enqueue(ShadowWorkRow.forEnqueue(
                HolmesShadowSampler.COMPARISON_KEY_PREFIX + seed.nativeRunId(), "COMPARISON",
                seed.nativeRunId(), seed.incidentId(), 2, seed.snapshot().hex(), 3));
        return stores.shadowWorks.claimBatch(OWNER, clock.now(), LEASE, 10).get(0);
    }

    private void executorSucceeds() {
        EvidencePackageV2 typed = parse(HOLMES_V2_PACKAGE);
        executor.next = RcaTaskExecutor.ExecutionResult.success(
                new RcaTaskExecutor.AttemptArtifact(2, ValidationStatus.STRUCTURE_VALIDATED,
                        List.of(), HOLMES_V2_PACKAGE, "raw", typed, List.of(),
                        Digest.sha256Of("raw"), Digest.sha256Of(HOLMES_V2_PACKAGE), "glm-5",
                        10, 20, 30, false, Map.of("sampling", "v1")));
    }

    private static EvidencePackageV2 parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return EvidencePackageV2.fromJson(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClaimStore.ClaimRow claimRow(UUID runId, String key, ClaimStatus status,
            String scope, String reason) {
        return new ClaimStore.ClaimRow(UUID.randomUUID(), runId, "fp-" + key,
                "hash-" + key, key, status, EvidenceBasis.SINGLE_SOURCE,
                ClaimLifecycle.ACTIVE, reason, scope, "10m", 2L,
                List.of("prometheus"), List.of("e1"), "ut-policy", Digest.sha256Of("s").hex(),
                null);
    }

    // ------------------------------------------------------------------ 对照分支

    @Test
    void comparisonLandsOnceWithShadowAnchorsAndQueuesCalibration() {
        Seed seed = seedNativeSucceeded();
        executorSucceeds();
        ShadowWorkRow row = enqueueComparison(seed);

        HolmesShadowWorker.Outcome outcome = worker().process(row);

        assertThat(outcome).isEqualTo(HolmesShadowWorker.Outcome.COMPARISON_LANDED);
        // 工作行收口：SUCCEEDED + tokens
        var work = stores.shadowWorks.findByShadowKey(row.shadowKey()).orElseThrow();
        assertThat(work.state()).isEqualTo("SUCCEEDED");
        assertThat(work.tokensSpent()).isEqualTo(30);
        // 影子锚三条：run 终态自收（非活跃）/task DONE 零认领面/attempt SUCCEEDED
        RcaRun shadowRun = stores.runs.all().stream()
                .filter(r -> !r.id().equals(seed.nativeRunId())).findFirst().orElseThrow();
        assertThat(shadowRun.state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(shadowRun.finishedAt()).isNotNull();
        assertThat(shadowRun.investigationHash()).isEqualTo(seed.snapshot());
        assertThat(shadowRun.trigger()).isEqualTo(RunTrigger.RERUN);
        RcaTask task = stores.tasks.findByRunId(shadowRun.id()).get(0);
        assertThat(task.state()).isEqualTo(RcaTaskState.DONE);
        assertThat(task.taskKey()).isEqualTo(RcaTask.HOLMES_INVESTIGATE);
        RcaAttempt attempt = stores.attempts.findByTaskId(task.id()).get(0);
        assertThat(attempt.status()).isEqualTo(RcaAttemptStatus.SUCCEEDED);
        assertThat(attempt.attemptNo()).isEqualTo(1);
        // 真执行器到达现场（executor 见到的就是影子 run）
        assertThat(executor.executedRunIds).containsExactly(shadowRun.id());
        // V32 对照行恰一次：holmes 侧预铸结论 / native 侧 claims 投影 / result 维差异
        assertThat(comparisons.rows).hasSize(1);
        var comparison = comparisons.rows.get(0);
        assertThat(comparison.nativeRunId()).isEqualTo(seed.nativeRunId());
        assertThat(comparison.shadowExecRef()).isEqualTo(HolmesShadowWorker.SHADOW_EXEC_REF);
        assertThat(comparison.holmesOutcome()).containsEntry("engine", "HOLMES")
                .containsEntry("total_tokens", 30);
        assertThat(comparison.nativeOutcome()).containsEntry("engine", "NATIVE");
        assertThat(comparison.disagreeFlags()).extracting(f -> f.get("dim"))
                .containsExactly(EngineComparisonRecorder.DIM_RESULT,
                        EngineComparisonRecorder.DIM_LATENCY);
        // 校准工作幂等入队
        assertThat(stores.shadowWorks.findByShadowKey(
                HolmesShadowWorker.CALIBRATION_KEY_PREFIX + seed.nativeRunId())).isPresent();
        // 零发布纪律
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
        assertThat(stores.winners.size()).isZero();
    }

    @Test
    void calibrationLandsNoiseBaselineIntoComparisonRow() {
        Seed seed = seedNativeSucceeded();
        executorSucceeds();
        worker().process(enqueueComparison(seed));

        // 校准执行：同结论 → 底噪 disagree=false
        executorSucceeds();
        var calibRow = stores.shadowWorks.claimBatch(OWNER, clock.now(), LEASE, 10).get(0);
        assertThat(calibRow.kind()).isEqualTo("CALIBRATION");

        HolmesShadowWorker.Outcome outcome = worker().process(calibRow);

        assertThat(outcome).isEqualTo(HolmesShadowWorker.Outcome.CALIBRATION_LANDED);
        assertThat(comparisons.rows).hasSize(2);
        var noiseRow = comparisons.rows.get(1);
        assertThat(noiseRow.nativeRunId()).isEqualTo(seed.nativeRunId());
        assertThat(noiseRow.noiseBaseline()).isNotNull();
        assertThat(noiseRow.noiseBaseline()).containsEntry("disagree", false)
                .containsEntry("native_run_id", seed.nativeRunId().toString());
        assertThat(noiseRow.nativeOutcome()).containsEntry("engine", "HOLMES_CALIB");
        assertThat(noiseRow.disagreeFlags()).isEmpty();
        var calibWork = stores.shadowWorks
                .findByShadowKey(HolmesShadowWorker.CALIBRATION_KEY_PREFIX + seed.nativeRunId())
                .orElseThrow();
        assertThat(calibWork.state()).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ 失败面

    @Test
    void executorFailureIsBoundedRetriedThenExhausted() {
        Seed seed = seedNativeSucceeded();
        executor.next = RcaTaskExecutor.ExecutionResult.retryable("TIMEOUT", "boom");
        HolmesShadowWorker w = worker();
        ShadowWorkRow row = enqueueComparison(seed);

        assertThat(w.process(row)).isEqualTo(HolmesShadowWorker.Outcome.FAILED);
        var work = stores.shadowWorks.findByShadowKey(row.shadowKey()).orElseThrow();
        assertThat(work.state()).isEqualTo("FAILED");
        assertThat(work.attempts()).isEqualTo(1);
        assertThat(work.lastError()).contains("TIMEOUT");
        // 影子 run 诚实 FAILED 终态（不悬挂活跃行）
        RcaRun shadowRun = stores.runs.all().stream()
                .filter(r -> !r.id().equals(seed.nativeRunId())).findFirst().orElseThrow();
        assertThat(shadowRun.state()).isEqualTo(RcaRunState.FAILED);
        assertThat(shadowRun.lastError()).isEqualTo("TIMEOUT");
        assertThat(comparisons.rows).isEmpty();

        // FAILED 可再认领（有界），耗尽 → EXHAUSTED 后不再入批
        w.process(claimAgain(row));
        w.process(claimAgain(row));
        var exhausted = stores.shadowWorks.findByShadowKey(row.shadowKey()).orElseThrow();
        assertThat(exhausted.state()).isEqualTo("EXHAUSTED");
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThat(stores.shadowWorks.claimBatch(OWNER, clock.now(), LEASE, 10)).isEmpty();
    }

    @Test
    void activeHolmesRunOnSameIncidentIsHonestFailedRetry() {
        Seed seed = seedNativeSucceeded();
        // 同 incident 活跃 HOLMES run（fallback 在途语义）→ 影子 run insert 撞部分唯一
        stores.runs.insert(new RcaRun(UUID.randomUUID(), seed.incidentId(), 2,
                RunTrigger.RERUN, RcaRunState.RUNNING, seed.snapshot(), NOW, NOW, NOW, null,
                null));
        executorSucceeds();
        ShadowWorkRow row = enqueueComparison(seed);

        assertThat(worker().process(row))
                .isEqualTo(HolmesShadowWorker.Outcome.INCIDENT_BUSY);
        var work = stores.shadowWorks.findByShadowKey(row.shadowKey()).orElseThrow();
        assertThat(work.state()).isEqualTo("FAILED");
        assertThat(work.lastError()).contains("INCIDENT_ACTIVE_HOLMES_RUN");
        assertThat(comparisons.rows).isEmpty();
        assertThat(executor.executedRunIds).isEmpty();
    }

    @Test
    void leaseExpiryReclaimMakesOriginalWorkerCasLoser() {
        Seed seed = seedNativeSucceeded();
        executorSucceeds();
        ShadowWorkRow row = enqueueComparison(seed);
        // 租约过期 → 他者回收重领（原持有者持有行已过期）
        clock.now = NOW.plus(LEASE).plusSeconds(1);
        stores.shadowWorks.claimBatch("thief", clock.now(), LEASE, 10);

        assertThat(worker().process(row)).isEqualTo(HolmesShadowWorker.Outcome.CAS_LOST);
        // V32 已幂等落账（重领者会再执行并再落幂等账——模糊窗成本，预算有界）
        assertThat(comparisons.rows).isNotEmpty();
    }

    @Test
    void missingAnchorFailsWorkWithoutExecution() {
        Seed seed = seedNativeSucceeded();
        executorSucceeds();
        stores.shadowWorks.enqueue(ShadowWorkRow.forEnqueue("holmes-shadow:ghost",
                "COMPARISON", UUID.randomUUID(), UUID.randomUUID(), 1,
                seed.snapshot().hex(), 3));
        ShadowWorkRow ghost = stores.shadowWorks.claimBatch(OWNER, clock.now(), LEASE, 10)
                .stream().filter(r -> r.shadowKey().equals("holmes-shadow:ghost")).findFirst()
                .orElseThrow();

        assertThat(worker().process(ghost)).isEqualTo(HolmesShadowWorker.Outcome.FAILED);
        var work = stores.shadowWorks.findByShadowKey("holmes-shadow:ghost").orElseThrow();
        assertThat(work.lastError()).contains("MISSING_ANCHOR");
        assertThat(executor.executedRunIds).isEmpty();
        assertThat(comparisons.rows).isEmpty();
    }

    @Test
    void calibrationWithoutBaselineRowFailsHonestly() {
        Seed seed = seedNativeSucceeded();
        executorSucceeds();
        stores.shadowWorks.enqueue(ShadowWorkRow.forEnqueue(
                HolmesShadowWorker.CALIBRATION_KEY_PREFIX + seed.nativeRunId(), "CALIBRATION",
                seed.nativeRunId(), seed.incidentId(), 2, seed.snapshot().hex(), 3));
        ShadowWorkRow calib = stores.shadowWorks
                .claimBatch(OWNER, clock.now(), LEASE, 10).get(0);

        assertThat(worker().process(calib)).isEqualTo(HolmesShadowWorker.Outcome.FAILED);
        var work = stores.shadowWorks
                .findByShadowKey(HolmesShadowWorker.CALIBRATION_KEY_PREFIX + seed.nativeRunId())
                .orElseThrow();
        assertThat(work.lastError()).contains("BASELINE_MISSING");
    }

    private ShadowWorkRow claimAgain(ShadowWorkRow claimed) {
        return stores.shadowWorks.claimBatch(OWNER, clock.now(), LEASE, 10).stream()
                .filter(r -> r.shadowKey().equals(claimed.shadowKey())).findFirst()
                .orElseThrow();
    }
}
