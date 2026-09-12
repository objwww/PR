package com.objwww.pr.control.alert.domain.agent;

import java.util.List;

/**
 * attempt 级用量聚合（R6/EV-06；§6.6 冻结的对账单位=attempt，EU18 重试逐次计后
 * 在此归并）。输入 = 同一 attempt 的 {@link RcaModelCallLedger.CallUsage} 行全集。
 *
 * <p>诚实规则：UNKNOWN 行（是否已执行不确定）或 SUCCESS+usage_missing 行（响应
 * 成功但供应商未回报用量）在场 → 该 attempt 期望总量不可知，整体 usage_missing
 * （不猜零不猜全）；仅 SUCCESS 带用量行求和；空集/仅 FAILED（无响应无用量）同
 * usage_missing。
 */
public final class RcaAttemptUsage {

    private RcaAttemptUsage() {
    }

    /** token 全 null = usage_missing（§6.2 冻结语义；与 UsageLedgerReconciler 同律） */
    public record Aggregated(Integer promptTokens, Integer completionTokens,
            Integer totalTokens, boolean usageMissing) {

        public static final Aggregated MISSING =
                new Aggregated(null, null, null, true);
    }

    public static Aggregated aggregate(List<RcaModelCallLedger.CallUsage> attemptRows) {
        boolean tainted = attemptRows.stream().anyMatch(r ->
                "UNKNOWN".equals(r.state())
                        || ("SUCCESS".equals(r.state()) && r.usageMissing()));
        if (tainted) {
            return Aggregated.MISSING;
        }
        int prompt = 0;
        int completion = 0;
        int total = 0;
        boolean any = false;
        for (RcaModelCallLedger.CallUsage row : attemptRows) {
            if ("SUCCESS".equals(row.state()) && !row.usageMissing()) {
                any = true;
                prompt += row.promptTokens();
                completion += row.completionTokens();
                total += row.totalTokens();
            }
        }
        return any ? new Aggregated(prompt, completion, total, false) : Aggregated.MISSING;
    }
}
