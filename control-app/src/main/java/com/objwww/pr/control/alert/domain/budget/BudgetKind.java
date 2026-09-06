package com.objwww.pr.control.alert.domain.budget;

/**
 * 预算维度（AM4 M4-08，V13 ck_run_budget_state_kind 同集冻结）。
 * TIME 不在其中——固定 deadline 语义留在 {@link RunBudget#checkDeadline}，不落账本。
 */
public enum BudgetKind {
    STEP, TOOL_CALL, EVIDENCE, SUBTASK, TOKEN, REPORT
}
