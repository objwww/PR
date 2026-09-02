package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.time.Instant;

/**
 * Router 决策输出（§4.2/附录 A）：封闭集，与附录 A 决策表一一对应。
 */
public sealed interface RouteDecision {

    /** 同路由退避重试 */
    record RetrySameRoute(Duration backoff) implements RouteDecision {
    }

    /** 切换到备路由 */
    record Fallback(ModelRoute nextRoute) implements RouteDecision {
    }

    /** 长等待挂回队列 */
    record Defer(Instant notBefore) implements RouteDecision {
    }

    /** 终态失败 */
    record Fail(String reason, boolean stepRetryable) implements RouteDecision {
    }
}
