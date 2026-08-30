package com.objwww.pr.control.domain.port;

/**
 * 只读 token 申请窄接口（评审修正 #6：Control↔Publisher 唯一直连点）。
 * 真实实现走 Publisher 的 CredentialBroker（M0-T14）；本任务仅有读环境变量的 stub。
 * token 只存内存，不落库不落日志。
 */
public interface CredentialTokenPort {

    /** 为指定 installation 申请仓库级、短期、只读 token */
    String requestReadOnlyToken(long installationId);
}
