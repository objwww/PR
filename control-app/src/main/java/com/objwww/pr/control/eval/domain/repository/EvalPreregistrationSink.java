package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 评测预登记落库面（ME-T12b/D09，V165 eval_preregistration；insert-only）。
 *
 * <p>批开始 eval_run 落行后立写登记（判定锚跑批之前冻结）：min_clusters =
 * 独立簇数下限（与 PairedTrialStats.MIN_CLUSTERS 门阈同源），prereg_digest =
 * sha256(runId|minClusters|registeredAt 截断到秒) 登记内容自证锚。唯一键
 * (eval_run_id)——一 run 一登记幂等，登记永不改写。落库失败不阻发批（调用方
 * fail-soft 记 warn），验收面读不到登记时质量面如实 INCONCLUSIVE 不猜。
 */
public interface EvalPreregistrationSink {

    void insert(UUID evalRunId, int minClusters, String preregDigest,
                Instant registeredAt);
}
