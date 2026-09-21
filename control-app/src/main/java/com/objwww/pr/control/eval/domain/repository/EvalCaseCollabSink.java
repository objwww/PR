package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 多 Agent 协作评测落库面（ME-T12/D07，V163 eval_case_collab；insert-only）。
 *
 * <p>沿 EvalCaseLoopSink 先例：逐案例 fail-soft 落档，缺席=未评如实（不冒充）。
 * 唯一键 (case_result_id, grader_version)——同轨迹同 grader 重算幂等，换 grader
 * 版本新行并存（历史不被重评覆盖）。观测标量（edge_count/admitted_count/
 * token_cost_total）可空 = 观测读失败 ERROR 行或 trace 缺失如实不出数；
 * jsonb 五列（checks/metrics/failure_labels/suspected_attributions/
 * supported_attributions）由调用方序列化（纯函数可测），本面只负责落行。
 */
public interface EvalCaseCollabSink {

    void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                String graderVersion, Integer edgeCount, Integer admittedCount,
                Long tokenCostTotal, String checksJson, String metricsJson,
                String failureLabelsJson, String suspectedAttributionsJson,
                String supportedAttributionsJson);
}
