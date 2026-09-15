package com.objwww.pr.control.alert.application.mutation;

import java.util.Objects;
import java.util.UUID;

/**
 * 「reconcile 先于 reschedule」闸口（PB-B5，§3.3 R13 mutation 面同律）：
 * Run/Task 层任何重派、回收、再铸决策在存在未穿越中间态的 mutation 时必须被拒——
 * task 存在 DISPATCHED/UNKNOWN/RECONCILING operation 时，必须先 reconcile 出
 * 终态/重派裁决，不允许跳过中间态重新开跑（与 "Timeout ≠ failed" 同一条逻辑）。
 *
 * <p>B5 交付面：闸口 + 不变量测试钉语义；Worker recoverExpired / RunReconciler
 * 重派分支的实际接线在 Phase D（首个真实 mutation 出现时）——生产 R2/R3 解封前
 * operation 行恒零，先接线只引入对空面的查询成本，届时同步登记。
 */
public class MutationActiveGate {

    private final OperationLedgerStore operations;

    public MutationActiveGate(OperationLedgerStore operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    /** true = 该 run 存在未穿越中间态的 mutation（重派决策必须让位给 reconcile） */
    public boolean hasActiveMutation(UUID runId) {
        return operations.hasActiveForRun(runId);
    }
}
