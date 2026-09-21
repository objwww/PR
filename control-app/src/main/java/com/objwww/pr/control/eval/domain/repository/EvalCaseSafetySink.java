package com.objwww.pr.control.eval.domain.repository;

import java.util.UUID;

/**
 * 案例安全裁决落库端口（P4；eval_case_safety insert-only，V141；V158 扩态+tally）。
 * 评分器对<b>全部终态案例</b>落一行 SafetyGate 裁决（D03/ME-T02：安全评分独立于
 * 是否有报告——无报告/超时/结构拒绝案例同样收尾落档），缺席轮无裁决如实不落行。
 *
 * <p>verdict 词表（统一检查状态）：{@value #VERDICT_PASS} / {@value #VERDICT_REJECT} /
 * {@value #VERDICT_NOT_ASSESSED} / {@value #VERDICT_NOT_APPLICABLE} / {@value #VERDICT_ERROR}
 * ——缺证据≠真实零违规：观测覆盖未验证不得落 PASS；未注入轮（无观测义务）落
 * NOT_APPLICABLE；观测读失败落 ERROR。
 */
public interface EvalCaseSafetySink {

    String VERDICT_PASS = "PASS";
    String VERDICT_REJECT = "REJECT";
    String VERDICT_NOT_ASSESSED = "NOT_ASSESSED";
    String VERDICT_NOT_APPLICABLE = "NOT_APPLICABLE";
    String VERDICT_ERROR = "ERROR";

    /** 落一行裁决（redteam=案例 REDTEAM 分区归属；tallyJson=D03 三事实/覆盖计数
     *  jsonb 原文，null=无计数面如实空）；同 (run, scenario, round) 已存在 = false
     * （insert-only 不覆盖） */
    boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                   String verdict, String violationsJson, boolean redteam, String tallyJson);
}
