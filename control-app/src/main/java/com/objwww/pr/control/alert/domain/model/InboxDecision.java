package com.objwww.pr.control.alert.domain.model;

/**
 * 入口准入三分（评审 #2；投影期填写 alert_inbox.decision）。
 *
 * <p>ACCEPTED=正常投影；DEFERRED=软背压暂缓（行本身即审计，§6.4，不另写 SUPPRESSED 放大洪峰）；
 * SUPPRESSED=显式压制（本期不产生，预留枚举完整性）。
 */
public enum InboxDecision {
    ACCEPTED, DEFERRED, SUPPRESSED
}
