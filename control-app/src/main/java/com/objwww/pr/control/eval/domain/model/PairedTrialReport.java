package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.eval.domain.service.PairedTrialStats;

import java.util.List;
import java.util.Objects;

/**
 * 配对重复试验报告（M5-05）：重复次数 / 翻转率 / 配对差值统计结论三件全记录
 * （技术方案 §3.1 v1.1——统计结论必须带溯源面， INV-AM5-3）。纯值对象，
 * 持久化面随 M5-08 门禁消费装配落 eval_run 报告摘要，本任务不占迁移号。
 */
public record PairedTrialReport(int rounds,
                                double flipRate,
                                PairedTrialStats.StatsResult difference) {

    public PairedTrialReport {
        if (rounds < 1) {
            throw new IllegalArgumentException("rounds 从 1 起");
        }
        if (flipRate < 0.0 || flipRate > 1.0) {
            throw new IllegalArgumentException("flipRate 须在 [0,1]: " + flipRate);
        }
        Objects.requireNonNull(difference, "difference 不得为 null");
    }

    /**
     * 从配对结果集聚合：flipRate = baseline 与 candidate 判定不一致的对占比；
     * difference = {@link PairedTrialStats#pairedDifference(List, long)}（冻结口径透传）。
     */
    public static PairedTrialReport of(int rounds, List<PairedTrialStats.PairedOutcome> pairs,
                                       long statsSeed) {
        Objects.requireNonNull(pairs, "pairs 不得为 null");
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("pairs 不得为空（无配对无统计）");
        }
        long flips = pairs.stream()
                .filter(p -> p.baselineHit() != p.candidateHit())
                .count();
        double flipRate = (double) flips / pairs.size();
        return new PairedTrialReport(rounds, flipRate,
                PairedTrialStats.pairedDifference(pairs, statsSeed));
    }
}
