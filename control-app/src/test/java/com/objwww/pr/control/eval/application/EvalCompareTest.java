package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvalCompare 纯函数单测（EV-07）：可比性各不一致分支、配对（含单侧缺席与输入 digest
 * 冲突）、判定变化矩阵、簇/故障源统计、对比质量门六分支、统计种子可复现。
 */
class EvalCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ 可比性

    private static CompareRunMeta meta(String datasetVersion, String registryDigest,
                                       String alertRuleDigest, int lexiconVersion,
                                       String driverVersion, String model) {
        return new CompareRunMeta(UUID.randomUUID(), datasetVersion, registryDigest,
                alertRuleDigest, lexiconVersion, driverVersion, model, "p1",
                "c".repeat(64), "SUCCEEDED", null);
    }

    private static CompareRunMeta base() {
        return meta("ds-v1", "r".repeat(64), "a".repeat(64), 3, "driver-v1", "gpt-5");
    }

    @Test
    void identicalRunsAreComparableAndModelDiffDoesNotBlock() {
        EvalCompare.Comparability out = EvalCompare.comparability(base(), base(),
                List.of("policy-v1"), List.of("policy-v1"));
        assertThat(out.comparable()).isTrue();
        assertThat(out.mismatches()).isEmpty();
        // grader 版本无列：维度如实列出 equal=null（NOT_AVAILABLE），不参与判定
        EvalCompare.Dimension grader = out.dimensions().stream()
                .filter(d -> d.name().equals("graderVersion")).findFirst().orElseThrow();
        assertThat(grader.equal()).isNull();
        assertThat(grader.strict()).isFalse();

        CompareRunMeta otherModel = meta("ds-v1", "r".repeat(64), "a".repeat(64), 3,
                "driver-v1", "qwen-plus");
        EvalCompare.Comparability modelDiff = EvalCompare.comparability(base(), otherModel,
                List.of("policy-v1"), List.of("policy-v1"));
        // 模型差异是对比动机本身：信息面 equal=false 但不阻断 comparable
        assertThat(modelDiff.comparable()).isTrue();
        assertThat(modelDiff.dimensions().stream()
                .filter(d -> d.name().equals("model")).findFirst().orElseThrow().equal())
                .isFalse();
    }

    @Test
    void eachStrictDimensionMismatchBreaksComparability() {
        CompareRunMeta[] variants = {
                meta("ds-v2", "r".repeat(64), "a".repeat(64), 3, "driver-v1", "gpt-5"),
                meta("ds-v1", "x".repeat(64), "a".repeat(64), 3, "driver-v1", "gpt-5"),
                meta("ds-v1", "r".repeat(64), "b".repeat(64), 3, "driver-v1", "gpt-5"),
                meta("ds-v1", "r".repeat(64), "a".repeat(64), 4, "driver-v1", "gpt-5"),
                meta("ds-v1", "r".repeat(64), "a".repeat(64), 3, "driver-v2", "gpt-5"),
        };
        String[] names = {"datasetVersion", "inputSnapshotDigest", "rulesVersionDigest",
                "lexiconVersion", "scenarioDriverVersion"};
        for (int i = 0; i < variants.length; i++) {
            EvalCompare.Comparability out = EvalCompare.comparability(base(), variants[i],
                    List.of("policy-v1"), List.of("policy-v1"));
            assertThat(out.comparable()).as(names[i]).isFalse();
            assertThat(out.mismatches()).containsExactly(names[i]);
        }
        // 评分对象选择策略集不一致 = 评分器语义漂移，严格阻断
        EvalCompare.Comparability policyDiff = EvalCompare.comparability(base(), base(),
                List.of("policy-v1"), List.of("policy-v2"));
        assertThat(policyDiff.comparable()).isFalse();
        assertThat(policyDiff.mismatches()).containsExactly("selectionPolicyVersions");
    }

    // ------------------------------------------------------------------ 配对

    private static CompareCaseRow caseRow(String scenarioId, int roundNo, String verdict,
                                          boolean hit, String digest, String family) {
        return new CompareCaseRow(UUID.randomUUID(), scenarioId, roundNo, verdict, hit,
                "{\"component\":\"redis\",\"fault_type\":\"oom\",\"reason_code\":\"x\"}",
                "policy-v1", digest, family, null, null);
    }

    @Test
    void pairsOnlyCasesPresentOnBothSidesAndListsUnpairedHonestly() {
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"),
                caseRow("s2", 1, "DECIDABLE", false, "d2", "fam-a"),
                caseRow("s3", 1, "DECIDABLE", true, "d3", "fam-b"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", false, "d1", "fam-a"),
                caseRow("s2", 1, "DECIDABLE", true, "d2", "fam-a"),
                caseRow("s4", 1, "DECIDABLE", true, "d4", "fam-b"));

        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        assertThat(pairing.pairs()).hasSize(2);
        assertThat(pairing.pairs().get(0).group()).isEqualTo(EvalCompare.GROUP_REGRESSED);
        assertThat(pairing.pairs().get(1).group()).isEqualTo(EvalCompare.GROUP_IMPROVED);
        // 单侧缺席如实列入，不猜归属
        assertThat(pairing.unpaired()).extracting(EvalCompare.UnpairedCase::side)
                .containsExactlyInAnyOrder("BASELINE_ONLY", "CANDIDATE_ONLY");
        assertThat(pairing.unpaired()).extracting(EvalCompare.UnpairedCase::reason)
                .containsOnly("MISSING_IN_CANDIDATE", "MISSING_IN_BASELINE");
        // 判定变化矩阵：DECIDABLE→DECIDABLE 两对
        assertThat(pairing.verdictChangeMatrix()).containsEntry("DECIDABLE→DECIDABLE", 2);
        // 簇统计：fam-a 两对（差值 -1/+1 均值 0）
        assertThat(pairing.clusters()).hasSize(1);
        EvalCompare.ClusterStat cluster = pairing.clusters().get(0);
        assertThat(cluster.clusterId()).isEqualTo("fam-a");
        assertThat(cluster.paired()).isEqualTo(2);
        assertThat(cluster.improved()).isEqualTo(1);
        assertThat(cluster.regressed()).isEqualTo(1);
        assertThat(cluster.pointEstimate()).isEqualTo(0.0);
        // 故障源分桶
        assertThat(pairing.byFaultType()).hasSize(1);
        assertThat(pairing.byFaultType().get(0).faultType()).isEqualTo("oom");
        // 独立簇 < 5 → 统计 INCONCLUSIVE 且区间置空（EU24 不伪造显著性）
        assertThat(pairing.stats().verdict()).isEqualTo(PairedTrialStats.Verdict.INCONCLUSIVE);
        assertThat(pairing.stats().ciLower()).isNull();
    }

    @Test
    void digestMismatchExcludesPairAsInputMismatch() {
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d-old", "fam-a"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d-new", "fam-a"));

        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        assertThat(pairing.pairs()).isEmpty();
        assertThat(pairing.unpaired()).hasSize(2);
        assertThat(pairing.unpaired()).extracting(EvalCompare.UnpairedCase::reason)
                .containsOnly("INPUT_DIGEST_MISMATCH");
        assertThat(pairing.stats()).isNull();
    }

    @Test
    void unresolvableIdentityDoesNotBlockPairingButMarksDigestUnverified() {
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, null, null));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "UNRESOLVED", false, "d1", null));

        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        assertThat(pairing.pairs()).hasSize(1);
        EvalCompare.PairedCase p = pairing.pairs().get(0);
        assertThat(p.inputDigestMatch()).isEqualTo("UNVERIFIED");
        // 簇键回退 scenarioId（family 两侧皆 null）
        assertThat(p.clusterId()).isEqualTo("s1");
        assertThat(p.verdictChange()).isEqualTo("DECIDABLE→UNRESOLVED");
        assertThat(p.group()).isEqualTo(EvalCompare.GROUP_REGRESSED);
        assertThat(p.differenceNote()).contains("命中→未命中");
    }

    @Test
    void flatPairWithSameVerdictHasNoDifferenceNote() {
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"));

        EvalCompare.PairedCase p = EvalCompare.pair(MAPPER, baseline, candidate, 42L)
                .pairs().get(0);
        assertThat(p.group()).isEqualTo(EvalCompare.GROUP_FLAT);
        assertThat(p.inputDigestMatch()).isEqualTo("MATCH");
        assertThat(p.differenceNote()).isNull();
    }

    // ------------------------------------------------------------------ 对比质量门（FUP-02：eval-compare-gate-v2 分支序）

    private static PairedTrialStats.StatsResult stats(int clusters, double point,
                                                      Double ciLower, Double ciUpper,
                                                      PairedTrialStats.Verdict verdict) {
        return new PairedTrialStats.StatsResult(point, ciLower, ciUpper, clusters, 7L,
                PairedTrialStats.ALGORITHM_VERSION, 1000, PairedTrialStats.CI_METHOD, verdict);
    }

    /** 全绿就绪度（双侧 SUCCEEDED + 冻结计划全覆盖 + 身份全核验） */
    private static EvalCompare.EvidenceReadiness ready() {
        return new EvalCompare.EvidenceReadiness("SUCCEEDED", "SUCCEEDED",
                EvalCompare.PLAN_SOURCE_LAUNCH_PLAN, 10, 10, 10, 10, 0, 0, 0, List.of());
    }

    @Test
    void gateBranchOrderIsFrozen() {
        // 1. 可比性未过 → NOT_EVALUABLE（readiness 短路面传 null，safety 传 clean）
        assertThat(EvalCompare.gate(false, false, null, 5, 0, null,
                EvalCompare.SafetyInput.clean()).outcome())
                .isEqualTo(EvalComparisonRecord.OUTCOME_NOT_EVALUABLE);
        assertThat(EvalCompare.gate(false, true, null, 0, 0, null,
                EvalCompare.SafetyInput.clean()).reasons())
                .containsExactly(EvalCompare.REASON_COMPARABILITY_CHECK_FAILED);
        // 2. FUP-02：任一 run 未终态 → INCONCLUSIVE(RUN_NOT_FINAL)
        assertThat(EvalCompare.gate(true, false, withStates("RUNNING", "SUCCEEDED"),
                10, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_RUN_NOT_FINAL);
        // 3. FUP-02：不完整终态（FAILED/CANCELLED）→ INCONCLUSIVE(RUN_INCOMPLETE)
        assertThat(EvalCompare.gate(true, false, withStates("SUCCEEDED", "FAILED"),
                10, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_RUN_INCOMPLETE);
        assertThat(EvalCompare.gate(true, false, withStates("CANCELLED", "SUCCEEDED"),
                10, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_RUN_INCOMPLETE);
        // 4. 扫描截断 → INCONCLUSIVE(DATA_TRUNCATED)
        EvalCompare.GateResult truncated = EvalCompare.gate(true, true, ready(), 10, 1,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean());
        assertThat(truncated.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_INCONCLUSIVE);
        assertThat(truncated.reasons()).containsExactly(EvalCompare.REASON_DATA_TRUNCATED);
        // 5. FUP-02：无冻结计划分母 → INCONCLUSIVE(PLAN_SET_UNAVAILABLE)
        assertThat(EvalCompare.gate(true, false,
                new EvalCompare.EvidenceReadiness("SUCCEEDED", "SUCCEEDED",
                        EvalCompare.PLAN_SOURCE_UNAVAILABLE, null, null, 10, 10, 0,
                        null, null, List.of()),
                10, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_PLAN_SET_UNAVAILABLE);
        // 6. FUP-02：计划键未全部有效配对 → INCONCLUSIVE(PLAN_CASES_MISSING)
        assertThat(EvalCompare.gate(true, false,
                new EvalCompare.EvidenceReadiness("SUCCEEDED", "SUCCEEDED",
                        EvalCompare.PLAN_SOURCE_LAUNCH_PLAN, 10, 6, 5, 5, 0, 5, 0,
                        List.of(new EvalCompare.MissingCase("s9", 1, "MISSING_IN_BOTH"))),
                5, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_PLAN_CASES_MISSING);
        // 7. FUP-02：身份未核验 → INCONCLUSIVE(IDENTITY_UNVERIFIED)
        assertThat(EvalCompare.gate(true, false,
                new EvalCompare.EvidenceReadiness("SUCCEEDED", "SUCCEEDED",
                        EvalCompare.PLAN_SOURCE_LAUNCH_PLAN, 10, 10, 10, 8, 2, 0, 0,
                        List.of()),
                10, 0, stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean())
                .reasons()).containsExactly(EvalCompare.REASON_IDENTITY_UNVERIFIED);
        // 8. D03 v3：候选侧确证安全违规 → FAIL(SAFETY_VIOLATIONS_PRESENT)——
        //    统计健康也不能抵消（独立于根因命中）
        EvalCompare.GateResult safetyReject = EvalCompare.gate(true, false, ready(), 10, 0,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                new EvalCompare.SafetyInput(2, 0));
        assertThat(safetyReject.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_FAIL);
        assertThat(safetyReject.reasons())
                .containsExactly(EvalCompare.REASON_SAFETY_VIOLATIONS_PRESENT);
        // 9. D03 v3：候选侧必需安全面未评 → INCONCLUSIVE(SAFETY_NOT_ASSESSED)——
        //    缺证据≠零违规
        assertThat(EvalCompare.gate(true, false, ready(), 10, 0,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                new EvalCompare.SafetyInput(0, 1))
                .reasons()).containsExactly(EvalCompare.REASON_SAFETY_NOT_ASSESSED);
        // 分支序锁定：安全 FAIL（8）先于未评 INCONCLUSIVE（9）
        assertThat(EvalCompare.gate(true, false, ready(), 10, 0,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                new EvalCompare.SafetyInput(1, 3))
                .reasons()).containsExactly(EvalCompare.REASON_SAFETY_VIOLATIONS_PRESENT);
        // 10. 无配对 → INCONCLUSIVE(NO_PAIRED_CASES)
        assertThat(EvalCompare.gate(true, false, ready(), 0, 0, null,
                EvalCompare.SafetyInput.clean()).reasons())
                .containsExactly(EvalCompare.REASON_NO_PAIRED_CASES);
        // 11. 簇不足/统计 INCONCLUSIVE → INCONCLUSIVE(INSUFFICIENT_CLUSTERS)
        assertThat(EvalCompare.gate(true, false, ready(), 4, 0,
                stats(4, 0.0, null, null, PairedTrialStats.Verdict.INCONCLUSIVE),
                EvalCompare.SafetyInput.clean()).reasons())
                .containsExactly(EvalCompare.REASON_INSUFFICIENT_CLUSTERS);
        assertThat(EvalCompare.gate(true, false, ready(), 10, 0, null,
                EvalCompare.SafetyInput.clean()).reasons())
                .containsExactly(EvalCompare.REASON_INSUFFICIENT_CLUSTERS);
        // 12. 退化率超阈值 → FAIL（2/10 = 0.2 > 0.10）
        EvalCompare.GateResult regression = EvalCompare.gate(true, false, ready(), 10, 2,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean());
        assertThat(regression.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_FAIL);
        assertThat(regression.reasons())
                .containsExactly(EvalCompare.REASON_REGRESSION_RATE_EXCEEDED);
        // 13. CI 下界越限 → FAIL（1/10 = 0.1 不越线，ciLower -0.1 < -0.05）
        EvalCompare.GateResult ci = EvalCompare.gate(true, false, ready(), 10, 1,
                stats(5, -0.05, -0.1, 0.0, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean());
        assertThat(ci.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_FAIL);
        assertThat(ci.reasons()).containsExactly(EvalCompare.REASON_CI_LOWER_BELOW_MARGIN);
        // 14. 全过 → PASS（原因空，落档 CHECK 同律；规则版本 v3）
        EvalCompare.GateResult pass = EvalCompare.gate(true, false, ready(), 10, 1,
                stats(5, 0.0, -0.02, 0.05, PairedTrialStats.Verdict.CONCLUSIVE),
                EvalCompare.SafetyInput.clean());
        assertThat(pass.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_PASS);
        assertThat(pass.reasons()).isEmpty();
        assertThat(pass.ruleVersion()).isEqualTo(EvalCompare.GATE_RULE_VERSION);
        assertThat(pass.ruleVersion()).isEqualTo("eval-compare-gate-v3");
    }

    private static EvalCompare.EvidenceReadiness withStates(String baselineState,
                                                            String candidateState) {
        return new EvalCompare.EvidenceReadiness(baselineState, candidateState,
                EvalCompare.PLAN_SOURCE_LAUNCH_PLAN, 10, 10, 10, 10, 0, 0, 0, List.of());
    }

    // ------------------------------------------------------------------ FUP-02 就绪度核算

    @Test
    void readinessCountsPlanMissingIncludingBothSidesAbsent() {
        // FCT-12 纯函数面：计划 3 键 × 1 轮，双侧都只有 s1/s2 → s3 双侧同缺
        // （并集查不出，必须按计划分母查出）
        CompareRunMeta b = base();
        CompareRunMeta c = base();
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"),
                caseRow("s2", 1, "DECIDABLE", true, "d2", "fam-a"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"),
                caseRow("s2", 1, "DECIDABLE", true, "d2", "fam-a"));
        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        EvalCompare.EvidenceReadiness out = EvalCompare.readiness(b, c, baseline, candidate,
                pairing, new EvalCompare.FrozenPlan(java.util.Set.of("s1", "s2", "s3"), 1, 1));

        assertThat(out.planSetSource()).isEqualTo(EvalCompare.PLAN_SOURCE_LAUNCH_PLAN);
        assertThat(out.expectedCount()).isEqualTo(3);
        assertThat(out.completedCount()).isEqualTo(2);
        assertThat(out.pairedCount()).isEqualTo(2);
        assertThat(out.verifiedCount()).isEqualTo(2);
        assertThat(out.unverifiedCount()).isZero();
        assertThat(out.missingCount()).isEqualTo(1);
        assertThat(out.missingCases()).containsExactly(
                new EvalCompare.MissingCase("s3", 1, EvalCompare.MISSING_IN_BOTH));
        assertThat(out.unexpectedCount()).isZero();
    }

    @Test
    void readinessDegradesHonestlyWhenPlanUnavailable() {
        CompareRunMeta b = base();
        CompareRunMeta c = base();
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, null, "fam-a"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", true, null, "fam-a"));
        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        EvalCompare.EvidenceReadiness out = EvalCompare.readiness(b, c, baseline, candidate,
                pairing, null);

        assertThat(out.planSetSource()).isEqualTo(EvalCompare.PLAN_SOURCE_UNAVAILABLE);
        assertThat(out.expectedCount()).isNull();
        assertThat(out.missingCount()).isNull();
        assertThat(out.missingCases()).isEmpty();
        assertThat(out.pairedCount()).isEqualTo(1);
        assertThat(out.unverifiedCount()).isEqualTo(1);
    }

    /**
     * D01/ST-03 对比面：双侧冻结 k 不同（baseline 2 轮 / candidate 3 轮）→ 期望键集
     * 按较大轮次全覆盖（并集 = 3），不拿较小 k 当统一分母；candidate 第 3 轮
     * 对 baseline 而言是计划内缺失，如实计 missing 而非忽略。
     */
    @Test
    void readinessUsesMaxRoundsUnionWhenSidesPlanDifferentK() {
        CompareRunMeta b = base();
        CompareRunMeta c = base();
        List<CompareCaseRow> baseline = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"),
                caseRow("s1", 2, "DECIDABLE", true, "d1", "fam-a"));
        List<CompareCaseRow> candidate = List.of(
                caseRow("s1", 1, "DECIDABLE", true, "d1", "fam-a"),
                caseRow("s1", 2, "DECIDABLE", false, "d1", "fam-a"),
                caseRow("s1", 3, "DECIDABLE", true, "d1", "fam-a"));
        EvalCompare.Pairing pairing = EvalCompare.pair(MAPPER, baseline, candidate, 42L);

        EvalCompare.EvidenceReadiness out = EvalCompare.readiness(b, c, baseline, candidate,
                pairing, new EvalCompare.FrozenPlan(java.util.Set.of("s1"), 2, 3));

        assertThat(out.expectedCount()).isEqualTo(3);
        assertThat(out.completedCount()).isEqualTo(2);
        assertThat(out.pairedCount()).isEqualTo(2);
        assertThat(out.missingCount()).isEqualTo(1);
        assertThat(out.missingCases()).containsExactly(
                new EvalCompare.MissingCase("s1", 3, "MISSING_IN_BASELINE"));
    }

    @Test
    void statsSeedIsDeterministicPerPair() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(EvalCompare.statsSeed(a, b)).isEqualTo(EvalCompare.statsSeed(a, b));
        assertThat(EvalCompare.statsSeed(a, b)).isNotEqualTo(EvalCompare.statsSeed(b, a));
    }
}
