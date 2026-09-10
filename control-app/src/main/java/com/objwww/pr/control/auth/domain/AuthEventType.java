package com.objwww.pr.control.auth.domain;

/**
 * 认证事件类型（EX-C3a 审计面首版三类）。
 *
 * <p>会话过期由容器会话失效产生 HTTP 401，不伪造"过期事件"行——落表行必须对应
 * 真实发生的用户动作（登录成功/登录失败/注销）。
 */
public enum AuthEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT
}
