package com.objwww.pr.control.alert.domain.budget;

/**
 * 预算扣减判定结果（AM4 M4-08）：allowed=false 时 consumed/remaining 为拒绝时刻的账面，
 * retryAfterMs 为空——硬预算耗尽没有"稍后重试就够"的语义（INV-AM4-9 耗尽路径零 LLM 调用），
 * 字段仅为契约完备预留（对账退款后调用方可再次询问）。
 */
public record BudgetProbe(boolean allowed, long consumed, long remaining, Long retryAfterMs) {

    public static BudgetProbe allowed(long consumed, long remaining) {
        return new BudgetProbe(true, consumed, remaining, null);
    }

    public static BudgetProbe rejected(long consumed, long remaining) {
        return new BudgetProbe(false, consumed, remaining, null);
    }
}
