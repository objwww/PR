package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.PairedTrialReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PairedTrialStats 冻结统计口径（M5-05；技术方案 §3.1 v1.1/E-17 逐条）：
 * 配对差值按 independence_group 聚类 + bootstrap 整组有放回重采样（默认 1000 次）；
 * **不再套 C/(C-1) 修正**（那是 clustered SE 的有限 cluster 修正，两算法不混写）；
 * 独立 cluster 不足返回 INCONCLUSIVE、区间置空——禁止收窄到零的假区间（INV-AM5-3）；
 * stats_seed/算法版本/重采样次数/CI 方法全记录。
 */
class PairedTrialStatsTest {

    private static PairedTrialStats.PairedOutcome pair(String cluster, boolean baseline,
                                                       boolean candidate) {
        return new PairedTrialStats.PairedOutcome(cluster, baseline, candidate);
    }

    // ---------------- 固定样例：点估计 = 簇均值的无权平均（与整组重采样同口径） ----------------

    @Test
    void pointEstimateIsUnweightedMeanOfClusterMeans() {
        // 簇 f1: 2 对差值 (0-1, 1-1) → 均值 -0.5；簇 f2: 1 对差值 (1-0) → 均值 +1
        // 簇级无权平均 = (-0.5 + 1) / 2 = 0.25（若按对池化 = ( -1+0+1 )/3 ≈ 0——口径区别在此）
        List<PairedTrialStats.PairedOutcome> pairs = List.of(
                pair("f1", true, false), pair("f1", true, true), pair("f2", false, true));

        PairedTrialStats.StatsResult result = PairedTrialStats.pairedDifference(pairs, 42L);

        assertThat(result.pointEstimate()).isEqualTo(0.25);
        assertThat(result.clusterCount()).isEqualTo(2);
    }

    // ---------------- 区间：整组重采样的分布性质（非同种子断言不钉数值） ----------------

    @Test
    void sameSeedReproducesIdenticalInterval() {
        List<PairedTrialStats.PairedOutcome> pairs = sampleFiveClusters();

        PairedTrialStats.StatsResult a = PairedTrialStats.pairedDifference(pairs, 7L);
        PairedTrialStats.StatsResult b = PairedTrialStats.pairedDifference(pairs, 7L);

        assertThat(a.ciLower()).isEqualTo(b.ciLower());
        assertThat(a.ciUpper()).isEqualTo(b.ciUpper());
        assertThat(a.statsSeed()).isEqualTo(7L);
        assertThat(b.statsSeed()).isEqualTo(7L);
    }

    @Test
    void allPositiveDifferencesGiveIntervalStrictlyAboveZero() {
        // 6 簇全部 candidate 命中 / baseline 未中：差值全 +1 → 区间整体在 0 之上
        List<PairedTrialStats.PairedOutcome> pairs = List.of(
                pair("c1", false, true), pair("c2", false, true), pair("c3", false, true),
                pair("c4", false, true), pair("c5", false, true), pair("c6", false, true));

        PairedTrialStats.StatsResult result = PairedTrialStats.pairedDifference(pairs, 1L);

        assertThat(result.verdict()).isEqualTo(PairedTrialStats.Verdict.CONCLUSIVE);
        assertThat(result.pointEstimate()).isEqualTo(1.0);
        assertThat(result.ciLower()).isGreaterThan(0.0);
        assertThat(result.ciUpper()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void intervalContainsPointEstimateAndResamplesAreRecorded() {
        List<PairedTrialStats.PairedOutcome> pairs = sampleFiveClusters();

        PairedTrialStats.StatsResult result = PairedTrialStats.pairedDifference(pairs, 99L);

        assertThat(result.ciLower()).isLessThanOrEqualTo(result.pointEstimate());
        assertThat(result.ciUpper()).isGreaterThanOrEqualTo(result.pointEstimate());
        // 全记录面（INV-AM5-3）：stats_seed/算法版本/重采样次数/CI 方法
        assertThat(result.statsSeed()).isEqualTo(99L);
        assertThat(result.algorithmVersion()).isEqualTo("cluster-bootstrap-v1");
        assertThat(result.resamples()).isEqualTo(1000);
        assertThat(result.ciMethod()).isEqualTo("cluster-bootstrap-95pct-nearest-rank");
    }

    // ---------------- INCONCLUSIVE：cluster 不足禁假区间 ----------------

    @Test
    void insufficientClustersReturnInconclusiveWithNullInterval() {
        // 4 簇 < MIN_CLUSTERS(5)：给结论 = 收窄假区间，只能 INCONCLUSIVE
        List<PairedTrialStats.PairedOutcome> pairs = List.of(
                pair("c1", true, false), pair("c2", false, true),
                pair("c3", true, true), pair("c4", false, false));

        PairedTrialStats.StatsResult result = PairedTrialStats.pairedDifference(pairs, 5L);

        assertThat(result.verdict()).isEqualTo(PairedTrialStats.Verdict.INCONCLUSIVE);
        assertThat(result.ciLower()).isNull();
        assertThat(result.ciUpper()).isNull();
        assertThat(result.pointEstimate()).isEqualTo(0.0);
        // 溯源面照常全记录（INCONCLUSIVE 也是一条可审计的统计结论）
        assertThat(result.clusterCount()).isEqualTo(4);
        assertThat(result.statsSeed()).isEqualTo(5L);
        assertThat(result.algorithmVersion()).isEqualTo("cluster-bootstrap-v1");
        assertThat(result.resamples()).isEqualTo(1000);
        assertThat(result.ciMethod()).isEqualTo("cluster-bootstrap-95pct-nearest-rank");
    }

    @Test
    void clusterCountBoundaryAtFiveIsConclusive() {
        List<PairedTrialStats.PairedOutcome> pairs = sampleFiveClusters();

        PairedTrialStats.StatsResult result = PairedTrialStats.pairedDifference(pairs, 5L);

        assertThat(result.clusterCount()).isEqualTo(5);
        assertThat(result.verdict()).isEqualTo(PairedTrialStats.Verdict.CONCLUSIVE);
        assertThat(result.ciLower()).isNotNull();
        assertThat(result.ciUpper()).isNotNull();
    }

    // ---------------- 翻转率与 PairedTrialReport 全记录 ----------------

    @Test
    void flipRateIsDisagreementFractionAcrossPairs() {
        // 4 对中 2 对翻转（c1 与 c4 的 baseline/candidate 判定不同）→ 2/4 = 0.5
        List<PairedTrialStats.PairedOutcome> pairs = List.of(
                pair("c1", true, false), pair("c2", true, true),
                pair("c3", false, false), pair("c4", false, true));

        PairedTrialReport report = PairedTrialReport.of(3, pairs, 42L);

        assertThat(report.rounds()).isEqualTo(3);
        assertThat(report.flipRate()).isEqualTo(0.5);
        assertThat(report.difference().statsSeed()).isEqualTo(42L);
        // 报告内嵌统计结论的算法面全记录（透传 StatsResult）
        assertThat(report.difference().algorithmVersion()).isEqualTo("cluster-bootstrap-v1");
    }

    // ---------------- 输入契约防呆 ----------------

    @Test
    void rejectsEmptyPairsBlankClusterOrExceedingCapResamples() {
        assertThatThrownBy(() -> PairedTrialStats.pairedDifference(List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PairedTrialStats.pairedDifference(
                List.of(pair(" ", true, false)), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PairedTrialStats.pairedDifference(sampleFiveClusters(), 1L,
                PairedTrialStats.MAX_RESAMPLES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 5 簇混合结果样例（边界 CONCLUSIVE 用） */
    private static List<PairedTrialStats.PairedOutcome> sampleFiveClusters() {
        return List.of(
                pair("c1", true, false),   // -1
                pair("c2", false, true),   // +1
                pair("c3", true, true),    //  0
                pair("c4", false, false),  //  0
                pair("c5", false, true));  // +1
    }
}
