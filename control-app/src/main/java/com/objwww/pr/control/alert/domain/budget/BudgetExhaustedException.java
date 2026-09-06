package com.objwww.pr.control.alert.domain.budget;

/**
 * 预算耗尽（AM4 M4-08）：硬上限不可透支（INV-AM4-5），越界即抛，由调用方确定性升级人工。
 */
public class BudgetExhaustedException extends RuntimeException {

    public BudgetExhaustedException(RunBudget.Kind kind, long limit, long consumed, long requested) {
        super("预算耗尽: " + kind + " 上限=" + limit + " 已扣=" + consumed
                + " 本次请求=" + requested);
    }

    private BudgetExhaustedException(String message) {
        super(message);
    }

    /** TIME 维度的 deadline 超期（固定 deadline 语义，非计数） */
    public static BudgetExhaustedException deadlineExceeded(long deadlineEpochMillis,
            long nowEpochMillis) {
        return new BudgetExhaustedException("预算耗尽: TIME deadline=" + deadlineEpochMillis
                + " 当前=" + nowEpochMillis);
    }

    /** 账本路径（V13 BudgetKind 六维）：预留被拒即耗尽，余额不足本次请求 */
    public static BudgetExhaustedException ofKind(BudgetKind kind, long consumed,
            long remaining, long requested) {
        return new BudgetExhaustedException("预算耗尽: " + kind + " 已扣=" + consumed
                + " 余额=" + remaining + " 本次请求=" + requested);
    }
}
