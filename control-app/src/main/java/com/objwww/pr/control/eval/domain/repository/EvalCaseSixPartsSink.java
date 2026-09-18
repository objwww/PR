package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 六要素检出落库面（M-d T5，V152 eval_case_six_parts；insert-only）。
 *
 * <p>沿 EvalCaseSafetySink/EvalCaseJudgeSink 先例：逐案例 fail-soft 落档，
 * 缺席=未评如实（不冒充）；置信档位与 confidence 布尔同生共死（DB 约束兜底）。
 */
public interface EvalCaseSixPartsSink {

    void insert(UUID evalRunId, String scenarioId, int roundNo,
                boolean whatHappened, boolean rootCause, boolean evidenceBasis,
                boolean impact, boolean confidence, boolean recommendation,
                String confidenceLevel, boolean complete);
}
