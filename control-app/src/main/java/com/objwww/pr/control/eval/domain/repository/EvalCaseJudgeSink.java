package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * LLM-judge 裁决落库端口（P6-G7；eval_case_judge insert-only，V145）。
 * 仅对有报告的案例（DECIDABLE/UNRESOLVED）落行；judge 未启用/缺席=不落行
 * （缺席=未评如实，不填 0 冒充）；同 (run, scenario, round) 重复 = false。
 */
public interface EvalCaseJudgeSink {

    /** verdict 值域：PASS / FAIL / ERROR */
    boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                   String rubricVersion, String model, String answersJson,
                   Integer passed, Integer total, String verdict, String error);
}
