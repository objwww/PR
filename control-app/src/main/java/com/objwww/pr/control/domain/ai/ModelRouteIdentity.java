package com.objwww.pr.control.domain.ai;

import java.util.Objects;

/**
 * checkpoint 契约身份（§4.7/I30）：provider/requestedModel/contractVersion 三段 canonical 格式。
 *
 * <p>与 reportedModel（供应商自报，可空、可能是别名）严格分离。
 * 不用响应元数据构造契约身份——契约身份必须可从配置确定性派生，与运行时响应无关。
 */
public record ModelRouteIdentity(
        String provider,
        String requestedModel,
        String contractVersion
) {
    public ModelRouteIdentity {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(requestedModel, "requestedModel");
        Objects.requireNonNull(contractVersion, "contractVersion");
    }

    /**
     * Canonical 格式：provider/requestedModel/contractVersion
     */
    public String toCanonicalString() {
        return provider + "/" + requestedModel + "/" + contractVersion;
    }

    /**
     * 从 canonical 格式解析
     */
    public static ModelRouteIdentity fromCanonicalString(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        String[] parts = canonical.split("/", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid canonical format: " + canonical);
        }
        return new ModelRouteIdentity(parts[0], parts[1], parts[2]);
    }
}
