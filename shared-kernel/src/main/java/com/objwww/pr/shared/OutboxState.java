package com.objwww.pr.shared;

/**
 * Outbox 命令八态机（架构冻结文档 v2.2 §1；漂移观测永不进 Outbox，无 CONFIRMED_STALE）。
 * 状态口径回答"尝试的历史"；资源现状见 publication_resource 表（PublicationResourceState）。
 */
public enum OutboxState {

    /** 待领取 */
    PENDING,
    /** 已领取，对外写调用在途（崩溃不确定窗口） */
    IN_FLIGHT,
    /** 对账中：禁盲目重发，须按 RemoteIdentityStrategy 探测远端 */
    RECONCILING,
    /** 退避等待重试 */
    RETRY_WAIT,
    /** 终态：远端副作用已确认存在（推进 last_resolved_sequence） */
    CONFIRMED,
    /** 终态：被新世代取代（推进 last_resolved_sequence） */
    SUPERSEDED,
    /** 终态：确定性失败（推进 last_resolved_sequence） */
    FAILED_TERMINAL,
    /** 终态：人工介入；不推进游标，阻塞同 PR 后续命令 */
    MANUAL
}
