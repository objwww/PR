package com.objwww.pr.publisher.infrastructure.credential;

/**
 * 写 token 的收窄 scope（v2.2 E6；T14 CredentialBroker 按 scope 铸造 installation token）。
 */
public enum TokenScope {
    /** CREATE/UPDATE_CHECK_RUN */
    CHECKS_WRITE,
    /** CREATE_REVIEW */
    PULL_REQUESTS_WRITE,
    /** reconcile 探测读 */
    READ
}
