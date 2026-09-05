package com.objwww.pr.control.alert.domain.budget;

/**
 * 预算耗尽（AM4 M4-08）：硬上限不可透支（INV-AM4-5），越界即抛，由调用方确定性升级人工。
 */
public class BudgetExhaustedException extends RuntimeException {

    public BudgetExhaustedException(RunBudget.Kind kind, long limit, long consumed, long requested) {
        super("预算耗尽: " + kind + " 上限=" + limit + " 已扣=" + consumed
                + " 本次请求=" + requested);
    }
}
