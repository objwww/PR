package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalCompareService 单测（EV-07；假端口纯函数段，EvalQueryServiceTest 同模式）：
 * 404/400 面、可比性 false 不出配对结论、三件套边界（分母 0）、group 过滤与键集
 * 游标分页、落档快照字段一致性、同对最新落档引用。
 */
class EvalCompareServiceTest {

    private final FakeReader reader = new FakeReader();
    private final FakeComparisons comparisons = new FakeComparisons();
    private final EvalCompareService service =
            new EvalCompareService(reader, comparisons, new ObjectMapper());

    private static final UUID BASELINE = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();

    private static CompareRunMeta meta(UUID runId) {
        return new CompareRunMeta(runId, "ds-v1", "r".repeat(64), "a".repeat(64), 3,
                "driver-v1", "gpt-5", "p1", "c".repeat(64), "SUCCEEDED");
    }

    private static CompareCaseRow caseRow(String scenarioId, int roundNo, String verdict,
                                          boolean hit, String family) {
        return caseRow(scenarioId, roundNo, verdict, hit, family, null, null);
    }

    /** R12 逐例差值输入面：rcaRunId（费用链）/ latencyMs 可注入 */
    private static CompareCaseRow caseRow(String scenarioId, int roundNo, String verdict,
                                          boolean hit, String family, UUID rcaRunId,
                                          Long latencyMs) {
        return new CompareCaseRow(UUID.randomUUID(), scenarioId, roundNo, verdict, hit,
                "{\"component\":\"redis\",\"fault_type\":\"oom\",\"reason_code\":\"x\"}",
                "policy-v1", "digest-" + scenarioId, family, rcaRunId, latencyMs);
    }

    private void setupComparableRuns() {
        reader.metaById.put(BASELINE, meta(BASELINE));
        reader.metaById.put(CANDIDATE, meta(CANDIDATE));
    }

    /** 5 簇 × 2 对全命中（持平）→ 统计 CONCLUSIVE、退化率 0 → 门 PASS */
    private void setupFiveFlatClusters() {
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            for (int r = 1; r <= 2; r++) {
                b.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
                c.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
            }
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
    }

    // ------------------------------------------------------------------ 400/404 面

    @Test
    void unknownRunIsEmptyAndSameRunRejected() {
        assertThat(service.compare(BASELINE, CANDIDATE, null, null, 200)).isEmpty();
        setupComparableRuns();
        assertThat(service.compare(BASELINE, UUID.randomUUID(), null, null, 200)).isEmpty();
        assertThat(service.compare(BASELINE, CANDIDATE, null, null, 200)).isPresent();
        assertThatThrownBy(() -> service.compare(BASELINE, BASELINE, null, null, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compare(BASELINE, CANDIDATE, "BOGUS", null, 200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("group");
        assertThatThrownBy(() -> service.compare(BASELINE, CANDIDATE, null, "garbage", 200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    // ------------------------------------------------------------------ 可比性 false 不出配对结论

    @Test
    void incomparableRunsYieldNoPairingConclusion() {
        setupComparableRuns();
        reader.metaById.put(CANDIDATE, new CompareRunMeta(CANDIDATE, "ds-v2", "r".repeat(64),
                "a".repeat(64), 3, "driver-v1", "gpt-5", "p1", "c".repeat(64), "SUCCEEDED"));
        setupFiveFlatClusters();

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();

        assertThat(out.comparability().comparable()).isFalse();
        assertThat(out.comparability().mismatches()).containsExactly("datasetVersion");
        assertThat(out.summary()).isNull();
        assertThat(out.cases()).isEmpty();
        assertThat(out.unpaired()).isEmpty();
        assertThat(out.gate().outcome()).isEqualTo("NOT_EVALUABLE");
        assertThat(out.gate().reasons()).containsExactly("COMPARABILITY_CHECK_FAILED");
        assertThat(out.asOf()).isNotNull();
    }

    // ------------------------------------------------------------------ 配对统计与三件套

    @Test
    void comparableRunsAssembleCountsMatrixClustersAndGate() {
        setupComparableRuns();
        setupFiveFlatClusters();
        // 加一对退化 + 一对改善 + 一条单侧缺席
        reader.casesByRun.get(BASELINE).add(caseRow("fam0-s", 3, "DECIDABLE", true, "fam0"));
        reader.casesByRun.get(CANDIDATE).add(caseRow("fam0-s", 3, "UNRESOLVED", false, "fam0"));
        reader.casesByRun.get(BASELINE).add(caseRow("fam1-s", 3, "UNRESOLVED", false, "fam1"));
        reader.casesByRun.get(CANDIDATE).add(caseRow("fam1-s", 3, "DECIDABLE", true, "fam1"));
        reader.casesByRun.get(BASELINE).add(caseRow("fam2-s", 3, "DECIDABLE", true, "fam2"));

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();

        assertThat(out.comparability().comparable()).isTrue();
        EvalCompareService.CompareSummary summary = out.summary();
        assertThat(summary.pairedCount()).isEqualTo(12);
        assertThat(summary.unpairedCount()).isEqualTo(1);
        // 三件套：分母 = pairedCount
        assertThat(summary.improved())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 12L, "OK"));
        assertThat(summary.regressed())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 12L, "OK"));
        assertThat(summary.flat()).isEqualTo(new EvalQueryService.RatioStat(10L, 12L, "OK"));
        // 判定变化矩阵
        assertThat(summary.verdictChangeMatrix())
                .containsEntry("DECIDABLE→UNRESOLVED", 1)
                .containsEntry("UNRESOLVED→DECIDABLE", 1)
                .containsEntry("DECIDABLE→DECIDABLE", 10);
        // 簇统计：5 簇
        assertThat(summary.clusters()).hasSize(5);
        // 配对统计溯源块（5 簇 CONCLUSIVE 边界：MIN_CLUSTERS=5）
        assertThat(summary.stats().clusterCount()).isEqualTo(5);
        assertThat(summary.stats().algorithmVersion())
                .isEqualTo(PairedTrialStatsAdapter.ALGORITHM_VERSION);
        // 门：退化率 1/12 ≈ 0.083 ≤ 0.10 → 看 CI（确定性种子下结果稳定，见断言）
        assertThat(out.gate().ruleVersion()).isEqualTo("eval-compare-gate-v1");
        assertThat(out.gate().outcome()).isIn("PASS", "FAIL");
        // 逐例列表与 unpaired
        assertThat(out.cases()).hasSize(12);
        assertThat(out.unpaired()).hasSize(1);
        assertThat(out.unpaired().get(0).side()).isEqualTo("BASELINE_ONLY");
        assertThat(out.unpaired().get(0).reason()).isEqualTo("MISSING_IN_CANDIDATE");
        assertThat(out.scanTruncated()).isFalse();
        assertThat(out.unpairedTruncated()).isFalse();
        // 无落档 → gateRecord null 如实
        assertThat(out.gateRecord()).isNull();
    }

    @Test
    void zeroPairedCasesYieldNotApplicableRatiosAndInconclusiveGate() {
        setupComparableRuns();
        reader.casesByRun.put(BASELINE, List.of(caseRow("s1", 1, "DECIDABLE", true, "fam-a")));
        reader.casesByRun.put(CANDIDATE, List.of(caseRow("s2", 1, "DECIDABLE", true, "fam-a")));

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();

        // 三件套边界：分母 0 → NOT_APPLICABLE（不填 0 率冒充）
        assertThat(out.summary().pairedCount()).isZero();
        assertThat(out.summary().improved().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(out.summary().regressed().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(out.summary().flat().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(out.summary().stats()).isNull();
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("NO_PAIRED_CASES");
        assertThat(out.unpaired()).hasSize(2);
    }

    // ------------------------------------------------------------------ group 过滤与游标分页

    @Test
    void groupFilterAndKeysetCursorPageThroughCases() {
        setupComparableRuns();
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            b.add(caseRow("s" + i, 1, "DECIDABLE", true, "fam-" + i));
            c.add(caseRow("s" + i, 1, "DECIDABLE", i % 2 == 0, "fam-" + i));
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);

        // group=REGRESSED：s1/s3 退化（candidate 未命中）
        EvalCompareService.EvalCompareResponse page1 =
                service.compare(BASELINE, CANDIDATE, "REGRESSED", null, 1).orElseThrow();
        assertThat(page1.cases()).hasSize(1);
        assertThat(page1.cases().get(0).scenarioId()).isEqualTo("s1");
        assertThat(page1.cases().get(0).group()).isEqualTo("REGRESSED");
        assertThat(page1.nextCursor()).isEqualTo("s1/1");

        EvalCompareService.EvalCompareResponse page2 =
                service.compare(BASELINE, CANDIDATE, "REGRESSED", page1.nextCursor(), 1)
                        .orElseThrow();
        assertThat(page2.cases()).hasSize(1);
        assertThat(page2.cases().get(0).scenarioId()).isEqualTo("s3");
        assertThat(page2.nextCursor()).isNull();

        // group=FLAT：s2/s4 持平
        EvalCompareService.EvalCompareResponse flat =
                service.compare(BASELINE, CANDIDATE, "FLAT", null, 10).orElseThrow();
        assertThat(flat.cases()).extracting(EvalCompareService.CompareCaseItem::scenarioId)
                .containsExactly("s2", "s4");
    }

    // ------------------------------------------------------------------ R12 逐例差值

    @Test
    void perCaseDeltaCarriesScoreCostLatencyWithHonestUnknowns() {
        setupComparableRuns();
        UUID baselineRca = UUID.randomUUID();
        UUID candidateRca = UUID.randomUUID();
        List<CompareCaseRow> b = List.of(
                caseRow("s1", 1, "DECIDABLE", false, "f1", baselineRca, 1_000L),
                caseRow("s2", 1, "DECIDABLE", true, "f1", null, null));
        List<CompareCaseRow> c = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "f1", candidateRca, 800L),
                caseRow("s2", 1, "DECIDABLE", true, "f1", null, null));
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
        // s1 链路：基线 priced 500_000；候选链路含 unpriced（cost NULL）→ Δcost UNKNOWN
        reader.usageRows = List.of(
                usage(BASELINE, baselineRca, 500_000L, "cny", false),
                usage(CANDIDATE, candidateRca, null, null, false));

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();

        assertThat(out.cases().get(0).scenarioId()).isEqualTo("s1");
        EvalCompareService.CaseDelta delta1 = out.cases().get(0).delta();
        assertThat(delta1.score().delta()).as("miss→hit = +1").isEqualTo(1L);
        assertThat(delta1.score().direction())
                .isEqualTo(EvalCompareService.MetricDelta.HIGHER_IS_BETTER);
        assertThat(delta1.score().group()).isEqualTo("IMPROVED");
        assertThat(delta1.cost().delta()).as("候选链路 unpriced → 不折算不为 0（R4）")
                .isNull();
        assertThat(delta1.cost().group()).isEqualTo("UNKNOWN");
        assertThat(delta1.costNote()).isEqualTo("CANDIDATE_COST_UNKNOWN");
        assertThat(delta1.latency().delta()).isEqualTo(-200L);
        assertThat(delta1.latency().direction())
                .isEqualTo(EvalCompareService.MetricDelta.LOWER_IS_BETTER);
        assertThat(delta1.latency().group()).as("latency 更短 = 改善").isEqualTo("IMPROVED");

        EvalCompareService.CaseDelta delta2 = out.cases().get(1).delta();
        assertThat(delta2.score().delta()).isZero();
        assertThat(delta2.score().group()).isEqualTo("FLAT");
        assertThat(delta2.cost().delta()).isNull();
        assertThat(delta2.costNote()).isEqualTo("BOTH_COST_UNKNOWN");
        assertThat(delta2.latency().group()).as("任一侧缺值如实 UNKNOWN").isEqualTo("UNKNOWN");
    }

    private static EvalQueryReader.UsageCallRow usage(UUID evalRunId, UUID rcaRunId,
            Long costMicros, String currency, boolean usageMissing) {
        return new EvalQueryReader.UsageCallRow(evalRunId, rcaRunId, UUID.randomUUID(),
                "primary", "SUCCESS", 100, 50, 150, costMicros,
                costMicros == null ? "unpriced" : "pv-1", currency, usageMissing);
    }

    // ------------------------------------------------------------------ 落档面（POST）

    @Test
    void recordPersistsFrozenSnapshotAndReturnsRecordRef() {
        setupComparableRuns();
        setupFiveFlatClusters();

        EvalCompareService.EvalCompareResponse out =
                service.record(BASELINE, CANDIDATE, "op-1").orElseThrow();

        assertThat(comparisons.inserted).hasSize(1);
        EvalComparisonRecord record = comparisons.inserted.get(0);
        assertThat(record.baselineRunId()).isEqualTo(BASELINE);
        assertThat(record.candidateRunId()).isEqualTo(CANDIDATE);
        assertThat(record.comparable()).isTrue();
        assertThat(record.pairedCount()).isEqualTo(10);
        assertThat(record.flatCount()).isEqualTo(10);
        assertThat(record.gateOutcome()).isEqualTo("PASS");
        assertThat(record.gateReasons()).isEmpty();
        assertThat(record.gateRuleVersion()).isEqualTo("eval-compare-gate-v1");
        assertThat(record.actor()).isEqualTo("op-1");
        assertThat(record.dimensionDiffsJson()).contains("datasetVersion");
        assertThat(record.statsSnapshotJson()).contains("cluster-bootstrap-v1");
        // 响应携带本行落档引用
        assertThat(out.gateRecord().recordId()).isEqualTo(record.id());
        assertThat(out.gateRecord().outcome()).isEqualTo("PASS");

        // 重落档 = 换新 id 一行（insert-only 不覆盖）；GET 面引用最新落档
        service.record(BASELINE, CANDIDATE, "op-1");
        assertThat(comparisons.inserted).hasSize(2);
        assertThat(comparisons.inserted.get(1).id()).isNotEqualTo(record.id());
        EvalCompareService.EvalCompareResponse live =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(live.gateRecord().recordId())
                .isEqualTo(comparisons.inserted.get(1).id());
    }

    @Test
    void recordRejectsSameRunAndUnknownRuns() {
        assertThatThrownBy(() -> service.record(BASELINE, BASELINE, "op-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.record(BASELINE, CANDIDATE, "op-1")).isEmpty();
        assertThat(comparisons.inserted).isEmpty();
    }

    // ------------------------------------------------------------------ 假端口

    private static final class FakeReader implements EvalQueryReader {
        final Map<UUID, CompareRunMeta> metaById = new LinkedHashMap<>();
        final Map<UUID, List<CompareCaseRow>> casesByRun = new LinkedHashMap<>();

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalQueryReader.EvalRunRow> findRun(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DatasetRow> listDatasets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartitionCountRow> listPartitionCounts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalCaseDetailRow> findCaseDetail(UUID runId, UUID caseExecutionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion,
                                                          String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId, List<UUID> evidenceIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId, String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CompareRunMeta> findCompareMeta(UUID runId) {
            return Optional.ofNullable(metaById.get(runId));
        }

        @Override
        public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
            return casesByRun.getOrDefault(runId, List.of());
        }

        @Override
        public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor,
                                                  int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCalls(UUID evalRunId) {
            return List.of();
        }

        /** R12 逐例差值注入面（默认空 = 全链路无 usage，Δcost 如实 UNKNOWN） */
        List<EvalQueryReader.UsageCallRow> usageRows = List.of();

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCallsForRuns(
                Iterable<UUID> evalRunIds) {
            return usageRows;
        }
    }

    private static final class FakeComparisons implements EvalComparisonRepository {
        final List<EvalComparisonRecord> inserted = new ArrayList<>();

        @Override
        public void insert(EvalComparisonRecord record) {
            inserted.add(record);
        }

        @Override
        public Optional<EvalComparisonRecord> findLatestByPair(UUID baselineRunId,
                                                               UUID candidateRunId) {
            return inserted.stream()
                    .filter(r -> r.baselineRunId().equals(baselineRunId)
                            && r.candidateRunId().equals(candidateRunId))
                    .reduce((a, b) -> b);
        }
    }

    /** PairedTrialStats 常量别名（断言可读性） */
    private static final class PairedTrialStatsAdapter {
        private static final String ALGORITHM_VERSION =
                com.objwww.pr.control.eval.domain.service.PairedTrialStats.ALGORITHM_VERSION;
    }
}
