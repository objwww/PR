package com.objwww.pr.control.alert.domain.model;

/**
 * rca_task 状态全集 11 态（AM1 六态 ∪ AM4 additive 新增五态，评审 v1.1 修正④冻结口径）。
 *
 * <p>AM1 旧六态（V1 work_item 同构 + V7 ck_rca_task_state，语义冻结不变）：
 * READY/RETRY_WAIT 可领取；LEASED 持租约；DONE/CANCELLED/DEAD 终态。
 *
 * <p>AM4 新增五态（V12 状态扩容迁移同步 DB 约束；WAITING_APPROVAL 属 AM5，AM4 不引入）：
 * BLOCKED（前置未了断，DAG 推进器视角待放行）；RUNNING（已领取进入执行中，区别于 LEASED 持租即算）；
 * SKIPPED（OPTIONAL 前置失败时按规约跳过，终态）；FAILED_TERMINAL（确定性失败不可重试，终态）；
 * STALE（generation 过期被新材料取代，终态）。
 *
 * <p>读取一律走 {@code RcaStateContract.parseTaskState}（新旧双读契约，fail-closed），
 * 不得直接 Enum.valueOf。
 */
public enum RcaTaskState {
    READY, LEASED, RETRY_WAIT, DONE, CANCELLED, DEAD,
    BLOCKED, RUNNING, SKIPPED, FAILED_TERMINAL, STALE
}
