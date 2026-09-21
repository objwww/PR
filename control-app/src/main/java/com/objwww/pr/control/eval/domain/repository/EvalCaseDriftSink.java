package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 上下文漂移评测落库面（ME-T12/D08，V164 eval_case_drift；insert-only）。
 *
 * <p>沿 EvalCaseCollabSink 先例：逐案例 fail-soft 落档，缺席=未评如实（不冒充）。
 * 唯一键 (case_result_id, grader_version)——同轨迹同 grader 重算幂等，换 grader
 * 版本新行并存（历史不被重评覆盖）。summary_digest 可空 = 无压缩事件
 * （NO_SUMMARY）或观测读失败 ERROR 行如实不出数；consumption jsonb 无消费观测
 * 如实落 "null"。jsonb 五列（consumption/checks/metrics/failure_labels/deferred）
 * 由调用方序列化（纯函数可测），本面只负责落行。
 */
public interface EvalCaseDriftSink {

    void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                String graderVersion, String summaryDigest, String consumptionJson,
                String checksJson, String metricsJson, String failureLabelsJson,
                String deferredJson);
}
