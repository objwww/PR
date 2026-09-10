package com.objwww.pr.control.auth.domain;

import java.time.Instant;

/**
 * 认证事件（EX-C3a 审计面；append-only，与 change_event 同律）。
 *
 * @param actor      认证主体；登录失败时为提交的用户名原文
 * @param eventType  事件类型
 * @param remoteAddr 单层代理面地址（X-Forwarded-For 首值或 request remoteAddr）
 * @param detail     失败原因类目等；不含密码、不含会话 id
 * @param occurredAt 事件时刻
 */
public record AuthEvent(String actor,
                        AuthEventType eventType,
                        String remoteAddr,
                        String detail,
                        Instant occurredAt) {

    public AuthEvent {
        java.util.Objects.requireNonNull(actor, "actor 不得为 null");
        java.util.Objects.requireNonNull(eventType, "eventType 不得为 null");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt 不得为 null");
    }
}
