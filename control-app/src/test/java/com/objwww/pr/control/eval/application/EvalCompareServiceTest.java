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
        return meta(runId, "SUCCEEDED", 2);
    }

    private static CompareRunMeta meta(UUID runId, String state) {
        return meta(runId, state, 2);
    }

    private static CompareRunMeta meta(UUID runId, String state, Integer rounds) {
        return new CompareRunMeta(runId, "ds-v1", "r".repeat(64), "a".repeat(64), 3,
                "driver-v1", "gpt-5", "p1", "c".repeat(64), state,
                launchPlanJson(rounds));
    }

    /** launch_plan 快照原文（EvalCommandService 契约字段名；rounds 空 = worker 默认 2） */
    private static String launchPlanJson(Integer rounds) {
        return "{\"displayName\":\"t\",\"mode\":\"E\",\"datasetVersion\":\"ds-v1\","
                + "\"roundsPerScenario\":" + (rounds == null ? "null" : rounds) + "}";
    }

    /** FUP-02 冻结计划分母：数据集 ds-v1 的案例键集登记 */
    private void setupPlan(String... caseKeys) {
        reader.planKeysByDataset.put("ds-v1", List.of(caseKeys));
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

    /** 5 簇 × 2 对全命中（持平）→ 统计 CONCLUSIVE、退化率 0 → 门 PASS（计划键同步登记） */
    private void setupFiveFlatClusters() {
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        List<String> planKeys = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            planKeys.add("fam" + f + "-s");
            for (int r = 1; r <= 2; r++) {
                b.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
                c.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
            }
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
        reader.planKeysByDataset.put("ds-v1", List.copyOf(planKeys));
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
                "a".repeat(64), 3, "driver-v1", "gpt-5", "p1", "c".repeat(64), "SUCCEEDED",
                launchPlanJson(2)));
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
        // FUP-02：r3 三行不在冻结计划（轮次 1..2）内 = unexpected，不阻断最终门
        assertThat(out.gate().ruleVersion()).isEqualTo("eval-compare-gate-v2");
        assertThat(out.gate().outcome()).isIn("PASS", "FAIL");
        assertThat(out.readiness().planSetSource()).isEqualTo("LAUNCH_PLAN");
        assertThat(out.readiness().expectedCount()).isEqualTo(10);
        assertThat(out.readiness().unexpectedCount()).isEqualTo(3);
        assertThat(out.readiness().verifiedCount()).isEqualTo(12);
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
        setupPlan("s1", "s2");
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
        // FUP-02 v2：计划内键全部未配对 → 先报 PLAN_CASES_MISSING（NO_PAIRED_CASES
        // 仍在分支序中兜底，但计划缺失原因更可解释）
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("PLAN_CASES_MISSING");
        assertThat(out.readiness().expectedCount()).isEqualTo(4);
        assertThat(out.readiness().missingCount()).isEqualTo(4);
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
        assertThat(record.gateRuleVersion()).isEqualTo("eval-compare-gate-v2");
        assertThat(record.actor()).isEqualTo("op-1");
        assertThat(record.dimensionDiffsJson()).contains("datasetVersion");
        assertThat(record.statsSnapshotJson()).contains("cluster-bootstrap-v1");
        // FUP-02：v2 落档携带就绪度快照（规则版本/阈值/计划分母核算）
        assertThat(record.readinessSnapshotJson()).contains("eval-compare-gate-v2")
                .contains("LAUNCH_PLAN").contains("\"expectedCount\":10")
                .contains("\"verifiedCount\":10");
        // 响应携带本行落档引用（含规则版本）
        assertThat(out.gateRecord().recordId()).isEqualTo(record.id());
        assertThat(out.gateRecord().outcome()).isEqualTo("PASS");
        assertThat(out.gateRecord().ruleVersion()).isEqualTo("eval-compare-gate-v2");

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

    // ------------------------------------------------------------------ FUP-02 质量门 v2（FCT-09 ~ FCT-17）

    /** FCT-09：候选 RUNNING → GET 可展示暂态差异，最终门不 PASS；POST 落档同口径 */
    @Test
    void fct09CandidateRunningShowsTransientDiffsButFinalGateNotPass() {
        setupComparableRuns();
        reader.metaById.put(CANDIDATE, meta(CANDIDATE, "RUNNING"));
        setupFiveFlatClusters();

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        // 暂态分析面照常：配对/逐例差异可展示
        assertThat(out.summary().pairedCount()).isEqualTo(10);
        assertThat(out.cases()).hasSize(10);
        // 最终门：只能 INCONCLUSIVE + RUN_NOT_FINAL
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("RUN_NOT_FINAL");
        assertThat(out.gate().ruleVersion()).isEqualTo("eval-compare-gate-v2");
        assertThat(out.readiness().candidateState()).isEqualTo("RUNNING");
        assertThat(out.readiness().expectedCount()).isEqualTo(10);

        // 落档 = 同一计算的非最终结论快照（允许保存诊断快照，但不是 PASS）
        EvalCompareService.EvalCompareResponse rec =
                service.record(BASELINE, CANDIDATE, "op-1").orElseThrow();
        EvalComparisonRecord record = comparisons.inserted.get(0);
        assertThat(record.gateOutcome()).isEqualTo("INCONCLUSIVE");
        assertThat(record.gateReasons()).containsExactly("RUN_NOT_FINAL");
        assertThat(record.gateRuleVersion()).isEqualTo("eval-compare-gate-v2");
        assertThat(record.readinessSnapshotJson()).contains("RUNNING")
                .contains("eval-compare-gate-v2");
        assertThat(rec.gate().outcome()).isEqualTo("INCONCLUSIVE");
    }

    /** FCT-10：FAILED/CANCELLED 不完整终态——局部命中率再好也不能成为最终 PASS */
    @Test
    void fct10FailedOrCancelledRunsCannotPassOnPartialHits() {
        setupComparableRuns();
        setupFiveFlatClusters();
        for (String state : List.of("FAILED", "CANCELLED")) {
            reader.metaById.put(CANDIDATE, meta(CANDIDATE, state));
            EvalCompareService.EvalCompareResponse out =
                    service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
            assertThat(out.gate().outcome()).as(state).isEqualTo("INCONCLUSIVE");
            assertThat(out.gate().reasons()).as(state).containsExactly("RUN_INCOMPLETE");
            service.record(BASELINE, CANDIDATE, "op-1").orElseThrow();
        }
        assertThat(comparisons.inserted).hasSize(2);
        assertThat(comparisons.inserted).allSatisfy(r -> {
            assertThat(r.gateOutcome()).isEqualTo("INCONCLUSIVE");
            assertThat(r.gateReasons()).containsExactly("RUN_INCOMPLETE");
        });
    }

    /** FCT-11：基线 50 例候选 5 例（缺 45），5 簇全命中 → INCONCLUSIVE + 分母可解释 */
    @Test
    void fct11CandidateMissing45PlanCasesIsInconclusiveWithExplainableDenominator() {
        setupComparableRuns();
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        List<String> planKeys = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            planKeys.add("sc" + i);
            for (int r = 1; r <= 2; r++) {
                b.add(caseRow("sc" + i, r, "DECIDABLE", true, "fam" + i % 5));
            }
        }
        // 候选只完成 5 例（5 个独立簇各 1 对且全部持平 = 局部命中率满分，v1 下曾 PASS）
        for (int i = 0; i < 5; i++) {
            c.add(caseRow("sc" + i, 1, "DECIDABLE", true, "fam" + i % 5));
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
        reader.planKeysByDataset.put("ds-v1", List.copyOf(planKeys));

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(out.summary().pairedCount()).isEqualTo(5);
        assertThat(out.summary().clusters()).hasSize(5);
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("PLAN_CASES_MISSING");
        // 分母可解释：计划 50 / 双侧完成 5 / 缺失 45（全为候选侧缺席）
        assertThat(out.readiness().expectedCount()).isEqualTo(50);
        assertThat(out.readiness().completedCount()).isEqualTo(5);
        assertThat(out.readiness().missingCount()).isEqualTo(45);
        assertThat(out.readiness().coverageRatio()).isEqualTo(0.1);
        assertThat(out.readiness().missingCases()).hasSize(45);
        assertThat(out.readiness().missingCases())
                .allSatisfy(m -> assertThat(m.reason()).isEqualTo("MISSING_IN_CANDIDATE"));
        assertThat(out.readiness().missingTruncated()).isFalse();

        service.record(BASELINE, CANDIDATE, "op-1").orElseThrow();
        assertThat(comparisons.inserted.get(0).gateOutcome()).isEqualTo("INCONCLUSIVE");
        assertThat(comparisons.inserted.get(0).gateReasons())
                .containsExactly("PLAN_CASES_MISSING");
    }

    /** FCT-12：两侧一起漏同一计划案例——按计划分母查出（并集检测不到），门不 PASS */
    @Test
    void fct12BothSidesMissingSamePlannedCaseIsCaughtByPlanDenominator() {
        setupComparableRuns();
        setupFiveFlatClusters();
        // 冻结计划含 fam9-s（双侧都未执行）：双侧键并集里根本没有它
        setupPlan("fam0-s", "fam1-s", "fam2-s", "fam3-s", "fam4-s", "fam9-s");

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(out.summary().pairedCount()).isEqualTo(10);
        assertThat(out.summary().unpairedCount()).isZero();
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("PLAN_CASES_MISSING");
        assertThat(out.readiness().expectedCount()).isEqualTo(12);
        assertThat(out.readiness().missingCount()).isEqualTo(2);
        assertThat(out.readiness().missingCases()).containsExactly(
                new EvalCompareService.MissingCaseItem("fam9-s", 1, "MISSING_IN_BOTH"),
                new EvalCompareService.MissingCaseItem("fam9-s", 2, "MISSING_IN_BOTH"));
    }

    /** FCT-13：5 对 digest 未知 → UNVERIFIED 可展示差异，但最终门不 PASS */
    @Test
    void fct13UnverifiedDigestsDisplayDiffsButDoNotPass() {
        setupComparableRuns();
        // 单轮计划（rounds=1）：排除轮次缺失干扰，只考身份核验分支
        reader.metaById.put(BASELINE, meta(BASELINE, "SUCCEEDED", 1));
        reader.metaById.put(CANDIDATE, meta(CANDIDATE, "SUCCEEDED", 1));
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        List<String> planKeys = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            planKeys.add("u" + f);
            // digest 双侧皆 null（身份不可解析）→ 配对成立但 inputDigestMatch=UNVERIFIED
            b.add(new CompareCaseRow(UUID.randomUUID(), "u" + f, 1, "DECIDABLE", true,
                    "{\"fault_type\":\"oom\"}", "policy-v1", null, "fam" + f, null, null));
            c.add(new CompareCaseRow(UUID.randomUUID(), "u" + f, 1, "DECIDABLE", true,
                    "{\"fault_type\":\"oom\"}", "policy-v1", null, "fam" + f, null, null));
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
        reader.planKeysByDataset.put("ds-v1", List.copyOf(planKeys));

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        // 可展示差异：配对与逐例行照常
        assertThat(out.summary().pairedCount()).isEqualTo(5);
        assertThat(out.cases()).hasSize(5);
        assertThat(out.cases()).allSatisfy(
                i -> assertThat(i.inputDigestMatch()).isEqualTo("UNVERIFIED"));
        // 最终门不 PASS：身份未核验
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("IDENTITY_UNVERIFIED");
        assertThat(out.readiness().verifiedCount()).isZero();
        assertThat(out.readiness().unverifiedCount()).isEqualTo(5);
    }

    /** FCT-14：已知 digest 冲突 → 保留原不配对保护（EU25），且最终门不 PASS */
    @Test
    void fct14DigestConflictStaysUnpairedAndBlocksFinalPass() {
        setupComparableRuns();
        setupFiveFlatClusters();
        for (int r = 1; r <= 2; r++) {
            reader.casesByRun.get(BASELINE).add(new CompareCaseRow(UUID.randomUUID(),
                    "conf-s", r, "DECIDABLE", true, "{\"fault_type\":\"oom\"}",
                    "policy-v1", "d-old", "fam0", null, null));
            reader.casesByRun.get(CANDIDATE).add(new CompareCaseRow(UUID.randomUUID(),
                    "conf-s", r, "DECIDABLE", true, "{\"fault_type\":\"oom\"}",
                    "policy-v1", "d-new", "fam0", null, null));
        }
        setupPlan("fam0-s", "fam1-s", "fam2-s", "fam3-s", "fam4-s", "conf-s");

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        // 原保护回归：冲突对不进配对/统计，双侧各记一条 INPUT_DIGEST_MISMATCH
        assertThat(out.summary().pairedCount()).isEqualTo(10);
        assertThat(out.unpaired()).hasSize(4);
        assertThat(out.unpaired()).allSatisfy(
                u -> assertThat(u.reason()).isEqualTo("INPUT_DIGEST_MISMATCH"));
        // v2 增强：计划键未全部有效配对 → 最终门不 PASS
        assertThat(out.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(out.gate().reasons()).containsExactly("PLAN_CASES_MISSING");
        assertThat(out.readiness().missingCases()).containsExactly(
                new EvalCompareService.MissingCaseItem("conf-s", 1, "INPUT_DIGEST_MISMATCH"),
                new EvalCompareService.MissingCaseItem("conf-s", 2, "INPUT_DIGEST_MISMATCH"));
    }

    /** FCT-15：完整计划 + 身份全 MATCH + 簇数充足 → 合法 PASS；旧 bootstrap 统计回归 */
    @Test
    void fct15CompletePlanVerifiedIdentityAndEnoughClustersPass() {
        setupComparableRuns();
        setupFiveFlatClusters();

        EvalCompareService.EvalCompareResponse out =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(out.gate().outcome()).isEqualTo("PASS");
        assertThat(out.gate().reasons()).isEmpty();
        assertThat(out.gate().ruleVersion()).isEqualTo("eval-compare-gate-v2");
        // 旧 bootstrap 统计回归：簇数/算法版本/溯源块不变
        assertThat(out.summary().stats().clusterCount()).isEqualTo(5);
        assertThat(out.summary().stats().algorithmVersion())
                .isEqualTo(PairedTrialStatsAdapter.ALGORITHM_VERSION);
        assertThat(out.summary().stats().verdict()).isEqualTo("CONCLUSIVE");
        assertThat(out.readiness().verifiedCount()).isEqualTo(10);
        assertThat(out.readiness().missingCount()).isZero();
        assertThat(out.readiness().coverageRatio()).isEqualTo(1.0);
    }

    /** FCT-16：簇不足 / 扫描超 10000 → INCONCLUSIVE 不被新就绪度逻辑放宽 */
    @Test
    void fct16InsufficientClustersAndScanTruncationStayInconclusive() {
        // 簇不足（4 簇 < MIN_CLUSTERS=5）：就绪度全绿也不出最终结论
        setupComparableRuns();
        List<CompareCaseRow> b = new ArrayList<>();
        List<CompareCaseRow> c = new ArrayList<>();
        List<String> planKeys = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            planKeys.add("fam" + f + "-s");
            for (int r = 1; r <= 2; r++) {
                b.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
                c.add(caseRow("fam" + f + "-s", r, "DECIDABLE", true, "fam" + f));
            }
        }
        reader.casesByRun.put(BASELINE, b);
        reader.casesByRun.put(CANDIDATE, c);
        reader.planKeysByDataset.put("ds-v1", List.copyOf(planKeys));
        EvalCompareService.EvalCompareResponse few =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(few.readiness().missingCount()).isZero();
        assertThat(few.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(few.gate().reasons()).containsExactly("INSUFFICIENT_CLUSTERS");

        // 单侧扫描 > 10000 → DATA_TRUNCATED（先于计划核算分支，不被吞没）
        List<CompareCaseRow> big = new ArrayList<>();
        for (int i = 0; i < 10_001; i++) {
            big.add(caseRow("bulk-s" + i, 1, "DECIDABLE", true, "fam" + i % 7));
        }
        reader.casesByRun.put(BASELINE, big);
        EvalCompareService.EvalCompareResponse truncated =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(truncated.scanTruncated()).isTrue();
        assertThat(truncated.gate().outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(truncated.gate().reasons()).containsExactly("DATA_TRUNCATED");
    }

    /** FCT-17：v1 历史快照不被 v2 覆盖——结论与规则版本原样保留，重新落档换新 id */
    @Test
    void fct17V1HistorySnapshotIsNotOverwrittenByV2() {
        setupComparableRuns();
        setupFiveFlatClusters();
        // 预置 v1 历史落档（无 readiness 快照，结论 PASS 原样保留 = 历史审计不改写）
        EvalComparisonRecord v1 = new EvalComparisonRecord(UUID.randomUUID(), BASELINE,
                CANDIDATE, true, "[]", 10, 0, 0, 0, 10, null, null, "PASS", List.of(),
                EvalCompare.GATE_RULE_VERSION_V1, "op-0", Instant.now().minusSeconds(3600));
        comparisons.inserted.add(v1);

        // GET：落档引用原样携带 v1 结论与规则版本（前端据此标"历史规则"）
        EvalCompareService.EvalCompareResponse live =
                service.compare(BASELINE, CANDIDATE, null, null, 200).orElseThrow();
        assertThat(live.gateRecord().outcome()).isEqualTo("PASS");
        assertThat(live.gateRecord().ruleVersion()).isEqualTo("eval-compare-gate-v1");
        // 实时计算面已是 v2 规则
        assertThat(live.gate().ruleVersion()).isEqualTo("eval-compare-gate-v2");

        // 重新落档 = 新 id 的 v2 行；v1 行原样保留（insert-only 不覆盖）
        service.record(BASELINE, CANDIDATE, "op-1").orElseThrow();
        assertThat(comparisons.inserted).hasSize(2);
        EvalComparisonRecord v2 = comparisons.inserted.get(1);
        assertThat(v2.id()).isNotEqualTo(v1.id());
        assertThat(v2.gateRuleVersion()).isEqualTo("eval-compare-gate-v2");
        assertThat(v2.readinessSnapshotJson()).isNotNull();
        EvalComparisonRecord history = comparisons.inserted.get(0);
        assertThat(history.gateRuleVersion()).isEqualTo("eval-compare-gate-v1");
        assertThat(history.gateOutcome()).isEqualTo("PASS");
        assertThat(history.readinessSnapshotJson()).isNull();
    }

    // ------------------------------------------------------------------ 假端口

    private static final class FakeReader implements EvalQueryReader {
        final Map<UUID, CompareRunMeta> metaById = new LinkedHashMap<>();
        final Map<UUID, List<CompareCaseRow>> casesByRun = new LinkedHashMap<>();
        /** FUP-02 冻结计划键集注入面（按数据集版本；缺省空 = 计划不可解析降级） */
        final Map<String, List<String>> planKeysByDataset = new LinkedHashMap<>();

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
        public List<String> listPlanCaseKeys(String datasetVersion) {
            return planKeysByDataset.getOrDefault(datasetVersion, List.of());
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

        @Override
        public List<EvalQueryReader.ScenarioRoundStatRow> listScenarioRoundStatsForRuns(
                Iterable<UUID> evalRunIds) {
            return List.of();
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
