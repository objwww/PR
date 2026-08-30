package com.objwww.pr.control.application;

/** T2 完成 Step 的结果分类（单测断言锚点）。 */
public enum T2Outcome {
    /** 晚到结果：attempt 记 STALE，Step/Run 不推进（I11） */
    STALE_IGNORED,
    /** Step 成功（REVIEW step 已登记 findings + 插 outbox 命令） */
    STEP_SUCCEEDED,
    /** 失败未耗尽预算：WorkItem RETRY_WAIT 退避，Step WAITING */
    RETRY_SCHEDULED,
    /** 失败且预算耗尽 / 确定性失败：Step FAILED，Run FAILED */
    STEP_FAILED
}
