package com.objwww.pr.control.alert.application.mutation;

import java.util.UUID;

/**
 * 意图落账后的审批推进回调（BA-171）：ToolGateway VALIDATE_ONLY 路径在意图行入账
 * 后调用——权威资源解析（fail-closed）→ 铸 approval_request（PENDING）。
 *
 * <p>纪律：实现自含事务（resolveAndRecord / request 各自短事务）；异常由调用方
 * （ToolGateway）捕获降级为日志——推进失败不炸调查，意图留 OPEN 未解析是诚实状态
 * （可人工 resolve/request 兜底）。可空挂载：null = 跳过（旧装配零漂移）。
 */
@FunctionalInterface
public interface IntentFollowUp {

    /**
     * @param intentId           已入账的意图 id（action_intent 行已提交）
     * @param requestedResourceKey 请求面资源键（可为 null 的场合由调用方先行拦截，
     *                             实现拿到的恒为非空键）
     */
    void onIntentRecorded(UUID intentId, String requestedResourceKey);
}
