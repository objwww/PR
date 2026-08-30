package com.objwww.pr.shared;

/**
 * 依赖终态判定结果（v2.2 E3 归类表）。
 */
public enum DependencyVerdict {

    /** 前置条件满足，放行 */
    PROCEED,
    /** 前置 SUPERSEDED 且依赖为 REQUIRE_*：级联 supersede 本命令（v2.1 修订二） */
    CASCADE_SUPERSEDE,
    /** 前置 FAILED_TERMINAL 且 REQUIRE_CONFIRMED：前置不可达，本命令终态取消 → SUPERSEDED */
    SUPERSEDE_SELF,
    /** 前置 MANUAL：等待人工判定，阻塞不推进 */
    WAIT_MANUAL
}
