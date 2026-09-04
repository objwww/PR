package com.objwww.pr.control.alert.domain.model;

/**
 * rca_run 六态（V7 ck_rca_run_state；终态集合与部分唯一索引谓词对齐）。
 *
 * <p>活跃 = QUEUED/RUNNING（uq_rca_run_active_incident 只约束这两个）；
 * SUPERSEDED = rerun 收尾时未完成被新 run 取代（§6.7 finishTask 算法）。
 */
public enum RcaRunState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, SUPERSEDED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
