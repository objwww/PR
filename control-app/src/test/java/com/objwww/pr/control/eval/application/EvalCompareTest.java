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
                "c".repeat(64), "SUCCEEDED");
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

    // ------------------------------------------------------------------ 对比质量门（eval-compare-gate-v1 六分支）

    private static PairedTrialStats.StatsResult stats(int clusters, double point,
                                                      Double ciLower, Double ciUpper,
                                                      PairedTrialStats.Verdict verdict) {
        return new PairedTrialStats.StatsResult(point, ciLower, ciUpper, clusters, 7L,
                PairedTrialStats.ALGORITHM_VERSION, 1000, PairedTrialStats.CI_METHOD, verdict);
    }

    @Test
    void gateBranchOrderIsFrozen() {
        // 1. 可比性未过 → NOT_EVALUABLE
        assertThat(EvalCompare.gate(false, false, 5, 0, null).outcome())
                .isEqualTo(EvalComparisonRecord.OUTCOME_NOT_EVALUABLE);
        assertThat(EvalCompare.gate(false, true, 0, 0, null).reasons())
                .containsExactly(EvalCompare.REASON_COMPARABILITY_CHECK_FAILED);
        // 2. 扫描截断 → INCONCLUSIVE(DATA_TRUNCATED)
        EvalCompare.GateResult truncated = EvalCompare.gate(true, true, 10, 1,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE));
        assertThat(truncated.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_INCONCLUSIVE);
        assertThat(truncated.reasons()).containsExactly(EvalCompare.REASON_DATA_TRUNCATED);
        // 3. 无配对 → INCONCLUSIVE(NO_PAIRED_CASES)
        assertThat(EvalCompare.gate(true, false, 0, 0, null).reasons())
                .containsExactly(EvalCompare.REASON_NO_PAIRED_CASES);
        // 4. 簇不足/统计 INCONCLUSIVE → INCONCLUSIVE(INSUFFICIENT_CLUSTERS)
        assertThat(EvalCompare.gate(true, false, 4, 0,
                stats(4, 0.0, null, null, PairedTrialStats.Verdict.INCONCLUSIVE)).reasons())
                .containsExactly(EvalCompare.REASON_INSUFFICIENT_CLUSTERS);
        assertThat(EvalCompare.gate(true, false, 10, 0, null).reasons())
                .containsExactly(EvalCompare.REASON_INSUFFICIENT_CLUSTERS);
        // 5. 退化率超阈值 → FAIL（2/10 = 0.2 > 0.10）
        EvalCompare.GateResult regression = EvalCompare.gate(true, false, 10, 2,
                stats(5, 0.0, 0.0, 0.0, PairedTrialStats.Verdict.CONCLUSIVE));
        assertThat(regression.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_FAIL);
        assertThat(regression.reasons())
                .containsExactly(EvalCompare.REASON_REGRESSION_RATE_EXCEEDED);
        // 6. CI 下界越限 → FAIL（1/10 = 0.1 不越线，ciLower -0.1 < -0.05）
        EvalCompare.GateResult ci = EvalCompare.gate(true, false, 10, 1,
                stats(5, -0.05, -0.1, 0.0, PairedTrialStats.Verdict.CONCLUSIVE));
        assertThat(ci.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_FAIL);
        assertThat(ci.reasons()).containsExactly(EvalCompare.REASON_CI_LOWER_BELOW_MARGIN);
        // 7. 全过 → PASS（原因空，落档 CHECK 同律）
        EvalCompare.GateResult pass = EvalCompare.gate(true, false, 10, 1,
                stats(5, 0.0, -0.02, 0.05, PairedTrialStats.Verdict.CONCLUSIVE));
        assertThat(pass.outcome()).isEqualTo(EvalComparisonRecord.OUTCOME_PASS);
        assertThat(pass.reasons()).isEmpty();
        assertThat(pass.ruleVersion()).isEqualTo(EvalCompare.GATE_RULE_VERSION);
    }

    @Test
    void statsSeedIsDeterministicPerPair() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(EvalCompare.statsSeed(a, b)).isEqualTo(EvalCompare.statsSeed(a, b));
        assertThat(EvalCompare.statsSeed(a, b)).isNotEqualTo(EvalCompare.statsSeed(b, a));
    }
}
