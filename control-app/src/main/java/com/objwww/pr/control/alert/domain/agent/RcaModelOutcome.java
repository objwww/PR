package com.objwww.pr.control.alert.domain.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * RCA 模型动作结果（R7a-1）：内容 + 用量摘要 + 对账锚（gatewayInvocationId 关联
 * 平台账本行；usageMissing=true 时 totalTokens 不可用于计费断言，费用未决）。
 */
public record RcaModelOutcome(
        UUID operationId,
        String content,
        String actualModel,
        long totalTokens,
        boolean usageMissing,
        String routeId,
        UUID gatewayInvocationId) {

    public RcaModelOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(actualModel, "actualModel");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(gatewayInvocationId, "gatewayInvocationId");
    }
}
