package com.objwww.pr.control.auth.domain;

/**
 * 认证事件仓储（EX-C3a 审计面）。append-only：只录不改不删。
 *
 * <p>同步落库、异常不吞——审计不可达时登录判负（审计 fail-closed；
 * B-28 律：生效而无证据 = 判负）。
 */
public interface AuthEventRepository {

    void record(AuthEvent event);
}
