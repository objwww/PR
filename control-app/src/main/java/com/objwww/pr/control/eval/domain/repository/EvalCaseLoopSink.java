package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 死循环评测落库面（ME-T12/D05，V162 eval_case_loop；insert-only）。
 *
 * <p>沿 EvalCaseBehaviorSink 先例：逐案例 fail-soft 落档，缺席=未评如实（不冒充）。
 * 唯一键 (case_result_id, grader_version)——同轨迹同 grader 重算幂等，换 grader
 * 版本新行并存（历史不被重评覆盖）。观测标量（stop_reason/detection_event_index/
 * first_no_progress_event_index/tokens_from_onset/seconds_from_onset）可空 =
 * 观测读失败 ERROR 行或数据缺失如实不出数；jsonb 三列（checks/metrics/
 * failure_labels）由调用方序列化（纯函数可测），本面只负责落行。
 */
public interface EvalCaseLoopSink {

    void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                String graderVersion, String stopReason,
                Integer detectionEventIndex, Integer firstNoProgressEventIndex,
                int postStopNewActions, long physicalCallsFromOnset, Long tokensFromOnset,
                Long secondsFromOnset, String checksJson, String metricsJson,
                String failureLabelsJson);
}
