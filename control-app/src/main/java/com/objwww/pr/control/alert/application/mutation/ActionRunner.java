package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.RcaOperation;

/**
 * Action Runner 端口（B v2 §1：Worker 提议意图，Runner 执行 mutation——执行权与
 * 调查权物理分离）。Phase B 只有 dry-run 实现：真实外部副作用面<b>物理不存在</b>
 * （A10；真实 Runner 随 Phase D 极小解锁引入，且需 Runner sandbox 三权分立）。
 *
 * <p>Runner 契约：网络 timeout ≠ failed——返回 {@code TIMEOUT_UNKNOWN} 时
 * Operation 进 UNKNOWN（锁保持 BUSY，reconcile 确认真实世界状态），不猜 FAILED。
 */
public interface ActionRunner {

    enum Outcome { EXECUTED, TIMEOUT_UNKNOWN }

    /**
     * dry-run 执行：仅验证派发参数完整性（digest/resource/params 在档），不触任何
     * 外部系统；返回模拟结局（SUCCEED 配置 → EXECUTED；CHAOS_TIMEOUT →
     * TIMEOUT_UNKNOWN，用于 UNKNOWN→RECONCILING 链演示）。
     */
    Outcome run(RcaOperation operation);
}
