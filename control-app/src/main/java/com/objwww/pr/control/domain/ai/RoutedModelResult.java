package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 带路由信息的模型结果（§3.1）：ModelResult + 路由身份 + 调用元数据。
 *
 * <p>不改 ModelResult 本体（兼容现有测试）；新增字段携带 Gateway 决策产生的路由信息。
 */
public record RoutedModelResult(
        ModelResult result,
        ModelRoute route,
        ModelRouteIdentity contractIdentity,
        UUID invocationId,
        int callSeq,
        String fallbackFrom,
        boolean usageMissing,
        Duration latency
) {
    public RoutedModelResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(contractIdentity, "contractIdentity");
        Objects.requireNonNull(invocationId, "invocationId");
        if (callSeq < 1) {
            throw new IllegalArgumentException("callSeq must be >= 1: " + callSeq);
        }
        Objects.requireNonNull(latency, "latency");
        // fallbackFrom 可为 null（首跳必须为 null）
    }
}
