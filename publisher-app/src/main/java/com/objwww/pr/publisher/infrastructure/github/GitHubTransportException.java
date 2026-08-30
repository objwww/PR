package com.objwww.pr.publisher.infrastructure.github;

/**
 * 传输层失败（超时/连接断/响应丢失）：本地与 GitHub 之间没有分布式事务，
 * "发出去了没有"未知——调用方（FencedPublicationExecutor）必须归为 OUTCOME_UNKNOWN
 * 进 RECONCILING，禁止盲目重发（EX-03/§4.3）。
 */
public class GitHubTransportException extends RuntimeException {

    public GitHubTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
