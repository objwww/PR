package com.objwww.pr.control.alert.domain.claim;

/**
 * 断言生命周期（AM4 M4-21 v1.3 统一状态模型三正交字段之三）：
 * ACTIVE = 当前投影；SUPERSEDED = 已被同 proposition 更新代际的观察取代。
 * 迁移单向 ACTIVE → SUPERSEDED。<b>历史不可变</b>：标 SUPERSEDED 只动 lifecycle 一列，
 * 状态/原因/证据引用/双哈希等内容字段永不改写（历史报告不撤销反改，评审裁定）。
 */
public enum ClaimLifecycle {
    ACTIVE, SUPERSEDED
}
