package com.objwww.pr.control.alert.domain.budget;

import java.util.Objects;
import java.util.UUID;

/**
 * 预算预留幂等业务键（AM4 M4-08 评审裁定）：UNIQUE(run_id, task_id, attempt_id, call_seq,
 * budget_kind)——同键重试读取同一笔 reservation，不得重新预留。
 *
 * <p>REPORT（最终报告专项预算）不归属任何 task/attempt：task/attempt 用 nil-UUID 哨兵。
 */
public record ReservationKey(UUID runId, UUID taskId, UUID attemptId, long callSeq,
        BudgetKind budgetKind) {

    /** REPORT 预算的 task/attempt 哨兵（V13 注释对齐） */
    public static final UUID REPORT_SENTINEL = new UUID(0L, 0L);

    public ReservationKey {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(budgetKind, "budgetKind");
        if (callSeq < 0) {
            throw new IllegalArgumentException("callSeq 必须 ≥0");
        }
    }

    /** 报告专项预算键（callSeq 约定 0——一 run 一报告） */
    public static ReservationKey forReport(UUID runId) {
        return new ReservationKey(runId, REPORT_SENTINEL, REPORT_SENTINEL, 0, BudgetKind.REPORT);
    }
}
