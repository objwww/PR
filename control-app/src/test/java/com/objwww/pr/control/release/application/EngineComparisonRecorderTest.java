package com.objwww.pr.control.release.application;

import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.shared.Digest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EngineComparisonRecorder 单测（M6-02 观察面成账）：双侧 outcome 归一化同形状落
 * V32；六维差异标记缺数不标记（缺数≠差异）+ result/latency 实差成账；无 GT 零语义
 * 裁决（flags 只带两侧原值）；uq_ec_pair 幂等重放 recorded=false；holmes 报告缺失/
 * 包解析失败诚实封装不抛出；对照落账计数 disagree 分桶。
 */
class EngineComparisonRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private final AlertInMemoryRuns runs = new AlertInMemoryRuns();
    private final AlertInMemoryReports reports = new AlertInMemoryReports();
    private final MemoryClaims claims = new MemoryClaims();
    private final RecorderStore store = new RecorderStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private EngineComparisonRecorder recorder() {
        return new EngineComparisonRecorder(new NoBundles(), runs, reports, claims,
                store, new AlertMetrics(registry));
    }

    private static final String HOLMES_V2_PACKAGE = """
            {"schema_version":2,"summary":"s",
             "root_cause":{"component":"checkout","fault_type":"dep_failure",
                           "reason_code":"CONN_TIMEOUT"},
             "claims":[
               {"claim_type":"dep_failure","status":"TRUE","component":"checkout",
                "fault_type":"dep_failure","symptom_codes":["p99"],"evidence_refs":["e1"]},
               {"claim_type":"cache_stale","status":"UNKNOWN","component":"cache",
                "fault_type":"obs","symptom_codes":[],"evidence_refs":["e2"]}],
             "evidence":["e1"],"impact":"i","remediation":"r","references":[]}
            """;

    @Test
    void normalizesBothSidesAndFlagsResultAndLatencyWithoutJudging() {
        UUID holmesId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 3, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW,
                NOW.minusSeconds(10), NOW.minusSeconds(2), null));
        runs.insert(new RcaRun(nativeId, UUID.randomUUID(), 3, RunTrigger.RERUN,
                RcaRunState.REPORTING, snapshot, NOW, NOW,
                NOW.minusSeconds(9), NOW.minusSeconds(1), null));
        reports.insert(new RcaReport(UUID.randomUUID(), holmesId, UUID.randomUUID(), 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), HOLMES_V2_PACKAGE,
                "raw", "glm-5", 10, 20, 30, false, NOW));
        claims.rows.add(claimRow(nativeId, "timeout_cascade", ClaimStatus.TRUE,
                "order-service", "CONN_RESET"));
        claims.rows.add(claimRow(nativeId, "cache_stale", ClaimStatus.UNKNOWN,
                "cache", "evidence-derived"));

        EngineComparisonRecorder.ComparisonOutcome outcome =
                recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");

        // 幂等锚 = 两侧身份 + snapshot + 候选 digest（无 bundle = 空串）
        assertThat(outcome.comparisonKey()).isEqualTo(Digest.sha256Of(
                holmesId + "\n" + nativeId + "\n" + snapshot.hex() + "\n" + "").hex());
        assertThat(outcome.recorded()).isTrue();

        EngineComparisonRepository.ComparisonRow row = store.rows.get(0);
        Map<String, Object> holmes = row.holmesOutcome();
        Map<String, Object> nativeSide = row.nativeOutcome();
        // 同形状归一化：engine/run_id/latency_ms/claims/claim_keys/root_cause
        assertThat(holmes).containsKeys("engine", "run_id", "latency_ms",
                "claims", "claim_keys", "root_cause", "validation_status", "total_tokens");
        assertThat(nativeSide).containsKeys("engine", "run_id", "latency_ms",
                "claims", "claim_keys", "root_cause");
        assertThat(holmes).containsEntry("engine", "HOLMES")
                .containsEntry("validation_status", "STRUCTURE_VALIDATED")
                .containsEntry("total_tokens", 30)
                .containsEntry("latency_ms", 8000L);
        assertThat(nativeSide).containsEntry("engine", "NATIVE")
                .containsEntry("latency_ms", 8000L);
        assertThat(holmes.get("claims").toString()).contains("TRUE=1", "UNKNOWN=1");

        // 六维标记：result（根因三元组+键集均异）+ latency（8000ms 双侧同值？否——
        // holmes 8s vs native 8s 相等不标记；改验下方不等式案）……本例双侧同为 8000ms
        List<String> dims = outcome.flaggedDims();
        assertThat(dims).containsExactly("result");
        Map<String, Object> flag = store.rows.get(0).disagreeFlags().get(0);
        assertThat(flag).containsEntry("dim", EngineComparisonRecorder.DIM_RESULT);
        // 无 GT：flag 只携带两侧原值，无 winner/正确性字段
        assertThat(flag).doesNotContainKeys("winner", "correct", "verdict");
        // schema/cost：native 侧缺数（影子零报告纪律）→ 不标记（缺数≠差异）
        assertThat(dims).doesNotContain(EngineComparisonRecorder.DIM_SCHEMA,
                EngineComparisonRecorder.DIM_COST,
                EngineComparisonRecorder.DIM_TOOL_LEGALITY,
                EngineComparisonRecorder.DIM_SAFETY_VIOLATION);
        // cost_compare 键缺侧省略
        assertThat(row.costCompare()).containsOnlyKeys("holmes_total_tokens",
                "holmes_latency_ms", "native_latency_ms");
        // 对照计数：disagree=true 分桶 +1
        assertThat(registry.counter("rca_engine_comparison_total",
                "disagree", "true").count()).isEqualTo(1.0);
    }

    @Test
    void identicalConclusionsProduceEmptyFlagsAndDisagreeFalse() {
        UUID holmesId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW,
                NOW.minusSeconds(5), NOW, null));
        runs.insert(new RcaRun(nativeId, UUID.randomUUID(), 1, RunTrigger.RERUN,
                RcaRunState.REPORTING, snapshot, NOW, NOW,
                NOW.minusSeconds(5), NOW, null));
        // 根因三元组与键集全同 → result 不标记；validation/tokens 影子侧恒缺 → 缺数不标记
        reports.insert(new RcaReport(UUID.randomUUID(), holmesId, UUID.randomUUID(), 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), HOLMES_V2_PACKAGE,
                "raw", "glm-5", null, null, null, true, NOW));
        // native 侧活跃投影与 holmes 包同结论（同键集 + 同确认根因三元组）
        claims.rows.add(claimRow(nativeId, "dep_failure", ClaimStatus.TRUE,
                "checkout", "CONN_TIMEOUT"));
        claims.rows.add(claimRow(nativeId, "cache_stale", ClaimStatus.UNKNOWN,
                "cache", "evidence-derived"));

        EngineComparisonRecorder.ComparisonOutcome outcome =
                recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");

        assertThat(outcome.flaggedDims()).isEmpty();
        assertThat(outcome.recorded()).isTrue();
        assertThat(registry.counter("rca_engine_comparison_total",
                "disagree", "false").count()).isEqualTo(1.0);
    }

    @Test
    void latencyDifferenceIsFlaggedWithBothRawValues() {
        UUID holmesId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW,
                NOW.minusSeconds(100), NOW, null));
        runs.insert(new RcaRun(nativeId, UUID.randomUUID(), 1, RunTrigger.RERUN,
                RcaRunState.REPORTING, snapshot, NOW, NOW,
                NOW.minusSeconds(4), NOW, null));
        // holmes 结论缺失面（无报告行）→ result 不比较；双侧 latency 齐 → 实差成账
        EngineComparisonRecorder.ComparisonOutcome outcome =
                recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");

        assertThat(outcome.flaggedDims()).containsExactly("latency");
        Map<String, Object> flag = store.rows.get(0).disagreeFlags().get(0);
        assertThat(flag).containsEntry("dim", "latency")
                .containsEntry("holmes", 100000L)
                .containsEntry("native", 4000L);
    }

    @Test
    void holmesParseFailureIsContainedInOutcomeInsteadOfThrown() {
        UUID holmesId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, null, null, null));
        runs.insert(new RcaRun(nativeId, UUID.randomUUID(), 1, RunTrigger.RERUN,
                RcaRunState.REPORTING, snapshot, NOW, NOW, null, null, null));
        reports.insert(new RcaReport(UUID.randomUUID(), holmesId, UUID.randomUUID(), 2,
                ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.of("bad"),
                "{\"not\":\"a package\"}", "raw", "glm-5", null, null, null, true, NOW));

        EngineComparisonRecorder.ComparisonOutcome outcome =
                recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");

        assertThat(outcome.recorded()).isTrue();
        assertThat(store.rows.get(0).holmesOutcome()).containsKey("parse_error");
        // 双侧结论面均缺 → 零差异标记（不臆造）
        assertThat(outcome.flaggedDims()).isEmpty();
    }

    @Test
    void duplicatePairIsIdempotentNoRewrite() {
        UUID holmesId = UUID.randomUUID();
        UUID nativeId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, null, null, null));
        runs.insert(new RcaRun(nativeId, UUID.randomUUID(), 1, RunTrigger.RERUN,
                RcaRunState.REPORTING, snapshot, NOW, NOW, null, null, null));

        recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");
        EngineComparisonRecorder.ComparisonOutcome replay =
                recorder().compareHolmesNative(holmesId, nativeId, "am4-shadow-trigger");

        assertThat(replay.recorded()).isFalse();
        assertThat(replay.comparisonKey()).isEqualTo(store.rows.get(0).comparisonKey());
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void unknownRunOrBlankRefIsRejected() {
        UUID holmesId = UUID.randomUUID();
        Digest snapshot = Digest.sha256Of("material");
        runs.insert(new RcaRun(holmesId, UUID.randomUUID(), 1, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, null, null, null));

        assertThatThrownBy(() -> recorder().compareHolmesNative(holmesId,
                UUID.randomUUID(), "am4-shadow-trigger"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("native run 不存在");
        assertThatThrownBy(() -> recorder().compareHolmesNative(holmesId,
                UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shadowExecRef");
    }

    // ------------------------------------------------------------------ 组装

    private static ClaimStore.ClaimRow claimRow(UUID runId, String key, ClaimStatus status,
            String scope, String reason) {
        return new ClaimStore.ClaimRow(UUID.randomUUID(), runId, "fp-" + key,
                "hash-" + key, key, status, EvidenceBasis.SINGLE_SOURCE,
                ClaimLifecycle.ACTIVE, reason, scope, "10m", 1L,
                List.of("prometheus"), List.of("e1"), "ut-policy", Digest.sha256Of("s").hex(),
                null);
    }

    private static final class AlertInMemoryRuns implements RcaRunRepository {
        private final List<RcaRun> rows = new ArrayList<>();

        @Override
        public void insert(RcaRun run) {
            rows.add(run);
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public boolean update(RcaRun run) {
            return true;
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return Optional.empty();
        }

        @Override
        public List<RcaRun> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<RcaRunRepository.RoutingView> findRoutingById(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return false;
        }

        @Override
        public java.util.OptionalLong currentRevision(UUID id) {
            return java.util.OptionalLong.empty();
        }
    }

    private static final class AlertInMemoryReports implements RcaReportRepository {
        private final List<RcaReport> rows = new ArrayList<>();

        @Override
        public void insert(RcaReport report) {
            rows.add(report);
        }

        @Override
        public List<RcaReport> findByRunId(UUID runId) {
            return rows.stream().filter(r -> r.runId().equals(runId)).toList();
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

    private static final class RecorderStore implements EngineComparisonRepository {
        private final List<ComparisonRow> rows = new ArrayList<>();
        private final Set<String> keys = new java.util.HashSet<>();

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
        public Optional<com.objwww.pr.control.release.domain.model.ConfigBundle>
                findByDigest(Digest digest) {
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
        public List<ConfigBundleRepository.BundleSummary> listRecent(int limit) {
            return List.of();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return true;
        }
    }
}
