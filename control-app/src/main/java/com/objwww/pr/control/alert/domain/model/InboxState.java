package com.objwww.pr.control.alert.domain.model;

/**
 * alert_inbox 六态（V3 webhook_inbox 同构）：RECEIVED→PROCESSING→{PROCESSED,RETRY_WAIT,IGNORED,DEAD_LETTER}。
 * 迁移表见 {@link com.objwww.pr.control.alert.domain.statemachine.InboxStateMachine}。
 */
public enum InboxState {
    RECEIVED, PROCESSING, RETRY_WAIT, PROCESSED, IGNORED, DEAD_LETTER
}
