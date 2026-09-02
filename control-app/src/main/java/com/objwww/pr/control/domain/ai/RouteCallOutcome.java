package com.objwww.pr.control.domain.ai;

import java.time.Duration;

/**
 * 单次路由调用结果（§3.1/附录 C）：成功或失败。
 */
public sealed interface RouteCallOutcome {

    /** 调用成功（reportedModel/providerRequestId 可空） */
    record Ok(
            String content,
            TokenUsage usage,
            boolean usageMissing,
            String reportedModel,
            String providerRequestId,
            Duration latency
    ) implements RouteCallOutcome {
    }

    /** 调用失败（httpStatus/retryAfter/providerCode 可空；providerCode 为供应商稳定错误码） */
    record Failed(
            ModelCallFailure failure,
            Integer httpStatus,
            Duration retryAfter,
            String providerCode,
            Duration latency
    ) implements RouteCallOutcome {
    }
}
