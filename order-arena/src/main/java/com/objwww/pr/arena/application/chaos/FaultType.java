package com.objwww.pr.arena.application.chaos;

/**
 * 靶场故障类型（与 arena.oa_chaos_session.fault_type 一致；冻结命名）。
 *
 * <p>存量三类（AM2 v3.0）：F1 幂等失效 / F2 状态回跳 / F3 超时未知。
 *
 * <p>M-a 业务交易链路扩编（v2 设计 §3.1，S16~S25）：
 * F9 掉单(回调丢弃) / F10 支付悬挂(发起沉默) / F11 重复扣款(重试跳幂等) /
 * F12 对账不平(库存偏差·数量差) / F13 库存超卖(竞态) / F14 消息丢失(履约未触发) /
 * F15 重复消费(ack 失败重复履约) / F16 断流(入口静默) / F17 履约积压(处理变慢)。
 * F18 金额错算——H7 HOLDOUT 专用预留（M-e 前不挂接演练、不进任何调优跑批）。
 */
public enum FaultType {
    F1,
    F2,
    F3,
    F9,
    F10,
    F11,
    F12,
    F13,
    F14,
    F15,
    F16,
    F17,
    F18
}
