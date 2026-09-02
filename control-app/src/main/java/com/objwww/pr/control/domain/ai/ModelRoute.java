package com.objwww.pr.control.domain.ai;

import java.util.Objects;

/**
 * 一条路由的完整身份（§4.3）：配置铸造，运行期不可变。
 *
 * <p>域从配置派生而非手配：endpointScope = 规范化 base-url；
 * quotaScope/credentialDomain = api-key SHA-256 截断派生值（永不落明文 key）。
 */
public record ModelRoute(
        String routeId,
        String requestedModel,
        String endpointScope,
        String quotaScope,
        String credentialDomain,
        String pricingVersion
) {
    public ModelRoute {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(requestedModel, "requestedModel");
        Objects.requireNonNull(endpointScope, "endpointScope");
        Objects.requireNonNull(quotaScope, "quotaScope");
        Objects.requireNonNull(credentialDomain, "credentialDomain");
        // pricingVersion 可为 null（无价格配置时）
    }
}
