package com.objwww.pr.control.alert.domain.dag;

import java.util.Set;

/**
 * DagPromoter.evaluate 的裁决输出（AM4 M4-06）：两个不相交的确定性集合。
 * ready = 可推进 READY 的 BLOCKED 任务；skipped = 因 REQUIRED 前驱终态未成功而
 * 收敛为 SKIPPED 的 BLOCKED 任务（原因恒为 DagPromoter.SKIP_REASON）。
 */
public record DagPromotion(Set<String> ready, Set<String> skipped) {

    public DagPromotion {
        ready = Set.copyOf(ready);
        skipped = Set.copyOf(skipped);
        for (String task : ready) {
            if (skipped.contains(task)) {
                throw new IllegalArgumentException("ready 与 skipped 必须不相交: " + task);
            }
        }
    }
}
