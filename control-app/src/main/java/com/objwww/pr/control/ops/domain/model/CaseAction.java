package com.objwww.pr.control.ops.domain.model;

/**
 * Case 动作全集（M5-11；ISA 18.2 式 action 驱动状态机的迁移入参）。
 * 命令四类（claim/ack/resolve/assign，M5-12 HTTP 面）+ 生命周期两侧
 * （CREATE 建单 / MERGE 同 fingerprint 复发聚合）+ SLA 升级（ESCALATE）。
 */
public enum CaseAction {
    CREATE,
    CLAIM,
    ACK,
    RESOLVE,
    ASSIGN,
    MERGE,
    ESCALATE
}
