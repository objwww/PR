package com.objwww.pr.control.alert.domain.model;

/**
 * alert_inbox 七态（V3 webhook_inbox 同构 + PA-A3 隔离区）：RECEIVED→PROCESSING→
 * {PROCESSED,RETRY_WAIT,IGNORED,DEAD_LETTER}；QUARANTINED = 注入扫描命中隔离区
 * （V113，初始态直插不经迁移，claim 面不领取，人工审核后经 QUARANTINED→RECEIVED
 * 放行边重驱——放行入口随 AM8 管理面）。
 * 迁移表见 {@link com.objwww.pr.control.alert.domain.statemachine.InboxStateMachine}。
 */
public enum InboxState {
    RECEIVED, PROCESSING, RETRY_WAIT, PROCESSED, IGNORED, DEAD_LETTER, QUARANTINED
}
