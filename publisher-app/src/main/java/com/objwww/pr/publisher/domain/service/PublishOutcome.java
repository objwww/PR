package com.objwww.pr.publisher.domain.service;

/**
 * FencedPublicationExecutor.execute 的结果口径（应用层计数/日志用）。
 */
public enum PublishOutcome {
    CONFIRMED,
    RECONCILING,
    RETRY_WAIT,
    SUPERSEDED,
    FAILED_TERMINAL,
    MANUAL,
    /** 未执行：前置未就绪 / 跳号待对账 / epoch 超前 / 租约已失效（僵尸放弃，B-2） */
    DEFERRED
}
