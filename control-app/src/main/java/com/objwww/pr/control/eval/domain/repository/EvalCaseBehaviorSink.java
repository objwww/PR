package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 行为评测落库面（ME-T04/D04，V160 eval_case_behavior；insert-only）。
 *
 * <p>沿 EvalCaseSafetySink/EvalCaseSixPartsSink 先例：逐案例 fail-soft 落档，
 * 缺席=未评如实（不冒充）。唯一键 (case_result_id, grader_version)——同轨迹
 * 同 grader 重算幂等，换 grader 版本新行并存（历史不被重评覆盖）。
 * jsonb 列（coverage/checks/metrics/failure_labels/evidence_refs）由调用方
 * 序列化（纯函数可测），本面只负责落行。
 */
public interface EvalCaseBehaviorSink {

    void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                String graderVersion, String traceDigest,
                String coverageJson, String checksJson, String metricsJson,
                String failureLabelsJson, String evidenceRefsJson);
}
