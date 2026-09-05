package com.objwww.pr.arena.application.chaos;

/**
 * 三类业务故障（AM2 v3.0 冻结命名，与 oa_chaos_session.fault_type 一致）：
 * F1 幂等失效 / F2 状态回跳 / F3 超时未知。
 */
public enum FaultType {
    F1,
    F2,
    F3
}
