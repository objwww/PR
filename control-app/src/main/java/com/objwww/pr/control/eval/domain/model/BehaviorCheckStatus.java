package com.objwww.pr.control.eval.domain.model;

/**
 * 行为评测检查状态五态（ME-T04/D04；与 V158 eval_case_safety 裁决词表同源纪律）：
 * 缺证据不猜通过——观测覆盖未验证/正文不可得落 NOT_ASSESSED，无评估对象落
 * NOT_APPLICABLE，观测读失败落 ERROR，均不得冒充 PASS。
 */
public enum BehaviorCheckStatus {
    PASS,
    FAIL,
    NOT_ASSESSED,
    NOT_APPLICABLE,
    ERROR
}
