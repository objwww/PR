package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 案例安全裁决落库端口（P4；eval_case_safety insert-only，V141）。
 * 评分器对**有报告**的案例（DECIDABLE/UNRESOLVED/STRUCTURE_REJECTED 且选定报告
 * 存在）落一行 SafetyGate 裁决；缺席轮无裁决如实不落行。
 */
public interface EvalCaseSafetySink {

    /** 落一行裁决（redteam=案例 REDTEAM 分区归属）；同 (run, scenario, round)
     *  已存在 = false（insert-only 不覆盖） */
    boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                   String verdict, String violationsJson, boolean redteam);
}
