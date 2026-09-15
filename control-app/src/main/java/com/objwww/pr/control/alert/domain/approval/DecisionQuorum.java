package com.objwww.pr.control.alert.domain.approval;

import java.util.List;

/**
 * 审批裁决域规则（PC-C1，设计基线 §2.6 R7）：双人确认 = <b>two distinct
 * principals</b> 且 distinct roles——一人同时持有 ONCALL+SECURITY 不算双人；
 * 同一 principal 对同一审批重复决策被 DB UNIQUE 结构性消灭（stub 列表只来自
 * 已落库行）。任一 denied = DENIED 终态（fail-closed）。
 */
public final class DecisionQuorum {

    public enum Outcome { APPROVED, DENIED, PENDING }

    public record Decision(String approverId, String approverRole, boolean approved) {
    }

    private DecisionQuorum() {
    }

    /**
     * @param requiredApprovers 1（R2 低危单批）或 2（R3/高危双人）
     */
    public static Outcome evaluate(int requiredApprovers, List<Decision> decisions) {
        if (requiredApprovers != 1 && requiredApprovers != 2) {
            throw new IllegalArgumentException("requiredApprovers 只允许 1/2");
        }
        long distinctPrincipals = decisions.stream().map(Decision::approverId).distinct()
                .count();
        boolean anyDenied = decisions.stream().anyMatch(d -> !d.approved());
        if (anyDenied) {
            return Outcome.DENIED;
        }
        long approvals = decisions.stream().filter(Decision::approved).count();
        if (approvals < requiredApprovers) {
            return Outcome.PENDING;
        }
        if (requiredApprovers == 2) {
            long distinctRoles = decisions.stream().map(Decision::approverRole).distinct()
                    .count();
            if (distinctPrincipals < 2 || distinctRoles < 2) {
                // 同 principal 双行（理论被 UNIQUE 挡）或同人兼两角/两人同角色：仍 PENDING
                return Outcome.PENDING;
            }
        }
        return Outcome.APPROVED;
    }
}
