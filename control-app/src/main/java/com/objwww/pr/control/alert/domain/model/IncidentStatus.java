package com.objwww.pr.control.alert.domain.model;

/**
 * Incident 二态事实（评审 #2：不含 INVESTIGATING/SUPPRESSED——执行态在 RcaRunState，准入态在 InboxDecision）。
 * generation 递增规则见 {@link com.objwww.pr.control.alert.domain.statemachine.IncidentStateMachine}。
 */
public enum IncidentStatus {
    FIRING, RESOLVED
}
