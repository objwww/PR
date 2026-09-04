package com.objwww.pr.control.alert.domain.model;

/**
 * 告警事实状态（AM 原文小写映射；alert_event.status / inbox.group_status 共用）。
 *
 * <p>状态三分（评审 #2）：告警事实（本枚举）/ RCA 执行态（RcaRunState 等）/ 入口准入（InboxDecision）。
 */
public enum AlertFiringStatus {

    FIRING("firing"),
    RESOLVED("resolved");

    private final String raw;

    AlertFiringStatus(String raw) {
        this.raw = raw;
    }

    /** AM 原文值（落库形态，小写） */
    public String raw() {
        return raw;
    }

    /** 解析 AM 原文；非法值抛 IllegalArgumentException（入口 400 路径已先行拦截，此处兜底） */
    public static AlertFiringStatus fromRaw(String raw) {
        for (AlertFiringStatus s : values()) {
            if (s.raw.equals(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知告警状态: " + raw);
    }
}
