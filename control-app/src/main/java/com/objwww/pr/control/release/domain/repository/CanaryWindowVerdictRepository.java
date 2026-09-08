package com.objwww.pr.control.release.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * canary_window_verdict 追加面（V30；M6-01）。insert-only——窗判定/双门明细/
 * 分层原始计数全审计；uq_cwv_window（rollout+三 digest+比例带+窗序+分级）=
 * 同窗幂等锚，重评不重记。digest 任一变化 = 新身份，旧矩阵自然作废。
 * 零框架（release.domain.repository 零框架规则覆盖）：jsonb 载荷以 Map 承载，
 * 序列化归基础设施。
 */
public interface CanaryWindowVerdictRepository {

    /** 追加一窗判定；返回 false = 该窗身份已记录（幂等，非错误） */
    boolean append(VerdictRow row);

    /**
     * 某 rollout+候选 digest 的窗序列（window_seq 升序）——连续 K 窗
     * （CanaryWindowEvaluator.hasConsecutivePasses）与状态只读面的断言源。
     */
    List<VerdictRow> findByRollout(UUID rolloutId, String candidateDigest);

    /** V30 canary_window_verdict 列全集合投影 */
    record VerdictRow(UUID rolloutId,
                      String candidateDigest,
                      String rolloutPolicyDigest,
                      String capabilityDigest,
                      int fromPercent,
                      int toPercent,
                      int windowSeq,
                      Instant windowStart,
                      Instant windowEnd,
                      String evidenceClass,
                      int eligibleIncidents,
                      Map<String, Object> rawCounts,
                      Map<String, Object> strata,
                      Map<String, Object> control,
                      Map<String, Object> absoluteSlo,
                      Boolean criticalPass,
                      Map<String, Object> scored,
                      String verdict,
                      List<String> evidenceRefs,
                      Instant evaluatedAt) {
    }
}
