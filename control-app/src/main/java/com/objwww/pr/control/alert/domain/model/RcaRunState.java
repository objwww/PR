package com.objwww.pr.control.alert.domain.model;

/**
 * rca_run 状态全集 9 态（AM1 六态 ∪ AM4 additive 新增三态，评审 v1.1 修正④冻结口径）。
 *
 * <p>AM1 旧六态（V7 ck_rca_run_state，语义冻结不变，不改名）：
 * SUPERSEDED = rerun 收尾时未完成被新 run 取代（§6.7 finishTask 算法）。
 *
 * <p>AM4 新增三态（V12 状态扩容迁移同步 DB 约束）：REPORTING（全部任务了断后的报告/裁决组装中）；
 * PARTIAL（预算或 SLA 耗尽下的部分完成收尾，终态）；EXPIRED（deadline 到期强制收尾，终态）。
 *
 * <p>活跃 = QUEUED/RUNNING/REPORTING（uq_rca_run_active_incident 部分唯一索引谓词在
 * V12 迁移同步扩含 REPORTING——报告组装期间同 incident 不得再开新 run）。
 * 读取一律走 {@code RcaStateContract.parseRunState}（新旧双读契约，fail-closed）。
 */
public enum RcaRunState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, SUPERSEDED,
    REPORTING, PARTIAL, EXPIRED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == REPORTING;
    }
}
