package com.objwww.pr.control.eval.domain.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 配对重复试验统计（M5-05；纯函数不触网，冻结口径 = 技术方案 §3.1 v1.1/E-17）：
 *
 * <ul>
 *   <li>配对差值按 independence group（independence_group_id / scenario_family_id）
 *       <b>聚类</b>，bootstrap <b>整组有放回重采样</b>（默认 1000 次）——重采样单位是
 *       簇而非对，区间 = bootstrap 分布的 95% nearest-rank 百分位；</li>
 *   <li><b>不再套 C/(C-1) 修正</b>：该修正是 clustered SE 的有限 cluster 校正项，
 *       与 cluster bootstrap 是两种算法，本项目冻结为只走 bootstrap（E-17：
 *       Inspect AI std.py 两段代码实为不同方法，不混写）；</li>
 *   <li>点估计与重采样同口径：簇内取对差值均值、跨簇<b>无权平均</b>（整组重采样的
 *       replicate 计算即此式，池化到对会破坏与区间的一致性）；</li>
 *   <li>独立 cluster 数不足（&lt;{@link #MIN_CLUSTERS}）→ {@code INCONCLUSIVE} 且区间
 *       置空——<b>禁止收窄到零的假区间</b>（INV-AM5-3）；溯源面（stats_seed/算法版本/
 *       重采样次数/CI 方法）任何结论形态下都全记录。</li>
 * </ul>
 *
 * <p>方向约定：difference = candidate − baseline（正值 = 候选更好）；flipRate =
 * baseline 与 candidate 判定不一致的对占比（{@code PairedTrialReport}）。
 */
public final class PairedTrialStats {

    /** 算法版本（冻结口径演进锚；改动聚类/重采样/百分位约定必须升版） */
    public static final String ALGORITHM_VERSION = "cluster-bootstrap-v1";
    /** CI 方法：簇级 bootstrap 95% nearest-rank 百分位 */
    public static final String CI_METHOD = "cluster-bootstrap-95pct-nearest-rank";
    /** 默认整组有放回重采样次数（技术方案 §3.1 冻结默认值） */
    public static final int DEFAULT_RESAMPLES = 1000;
    /** 重采样次数上限（防误配百万次重采样拖垮门禁计算） */
    public static final int MAX_RESAMPLES = 100_000;
    /**
     * 独立 cluster 数下限（C-9 裁定：低于 5 个整组重采样的分布不稳定，给出区间 =
     * 假精度；阈值入常量供 195 实测后校准）。
     */
    public static final int MIN_CLUSTERS = 5;

    /** 一对配对结果：同 case 在 baseline/candidate 两配置下的二值命中（同簇键聚类） */
    public record PairedOutcome(String clusterId, boolean baselineHit, boolean candidateHit) {
        public PairedOutcome {
            Objects.requireNonNull(clusterId, "clusterId 不得为 null");
            if (clusterId.isBlank()) {
                throw new IllegalArgumentException("clusterId 不得为 blank（聚类键）");
            }
        }

        double difference() {
            return (candidateHit ? 1 : 0) - (baselineHit ? 1 : 0);
        }
    }

    /** 统计结论（INV-AM5-3 全记录面；INCONCLUSIVE 时区间为 null，其余字段照实填写） */
    public record StatsResult(double pointEstimate, Double ciLower, Double ciUpper,
                              int clusterCount, long statsSeed, String algorithmVersion,
                              int resamples, String ciMethod, Verdict verdict) {
    }

    public enum Verdict {CONCLUSIVE, INCONCLUSIVE}

    private PairedTrialStats() {
    }

    /** 冻结默认面：1000 次重采样 */
    public static StatsResult pairedDifference(List<PairedOutcome> pairs, long statsSeed) {
        return pairedDifference(pairs, statsSeed, DEFAULT_RESAMPLES);
    }

    /**
     * 配对差值的簇级 bootstrap 区间。同 (pairs, seed, resamples) 输入必得同输出
     * （Random 的 LCG 算法跨 JVM 版本规范冻结，可复现锚）。
     */
    public static StatsResult pairedDifference(List<PairedOutcome> pairs, long statsSeed,
                                               int resamples) {
        Objects.requireNonNull(pairs, "pairs 不得为 null");
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("pairs 不得为空（无配对无统计）");
        }
        if (resamples < 1 || resamples > MAX_RESAMPLES) {
            throw new IllegalArgumentException("resamples 须在 1.." + MAX_RESAMPLES + ": "
                    + resamples);
        }

        // 聚类：保持首次出现序（deterministic）；簇内对差值均值 = 簇统计量
        Map<String, List<Double>> byCluster = new LinkedHashMap<>();
        for (PairedOutcome pair : pairs) {
            byCluster.computeIfAbsent(pair.clusterId(), k -> new ArrayList<>())
                    .add(pair.difference());
        }
        int clusterCount = byCluster.size();
        List<Double> clusterMeans = byCluster.values().stream()
                .map(PairedTrialStats::mean).toList();
        double pointEstimate = mean(clusterMeans);

        if (clusterCount < MIN_CLUSTERS) {
            // INV-AM5-3：不足 MIN_CLUSTERS 只给 INCONCLUSIVE——区间字段置空，
            // 禁止把不可靠分布包装成收窄到零的假区间
            return new StatsResult(pointEstimate, null, null, clusterCount, statsSeed,
                    ALGORITHM_VERSION, resamples, CI_METHOD, Verdict.INCONCLUSIVE);
        }

        // 整组有放回重采样：每次抽 clusterCount 个簇（可重复），replicate 统计量 =
        // 被抽簇均值的无权平均（与点估计同口径）
        Random random = new Random(statsSeed);
        double[] bootstrap = new double[resamples];
        for (int i = 0; i < resamples; i++) {
            double sum = 0;
            for (int c = 0; c < clusterCount; c++) {
                sum += clusterMeans.get(random.nextInt(clusterCount));
            }
            bootstrap[i] = sum / clusterCount;
        }
        java.util.Arrays.sort(bootstrap);
        Double ciLower = percentile(bootstrap, 0.025);
        Double ciUpper = percentile(bootstrap, 0.975);
        return new StatsResult(pointEstimate, ciLower, ciUpper, clusterCount, statsSeed,
                ALGORITHM_VERSION, resamples, CI_METHOD, Verdict.CONCLUSIVE);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** nearest-rank 百分位：rank = ceil(p × n)，sorted 升序取第 rank 位 */
    private static Double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
