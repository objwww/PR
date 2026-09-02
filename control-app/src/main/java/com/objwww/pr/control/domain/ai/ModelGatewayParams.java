package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * ModelGateway 的旋钮集合（§4.4/§4.9）：配置铸造，运行期不可变。
 * 构造即校验数值合法性（EX-39/40/41），非法配置在装配期就炸，不进运行期。
 */
public record ModelGatewayParams(
        int maxCallRetries,
        int maxPhysicalCallsPerStep,
        int maxPromptTokensPerCall,
        int maxCompletionTokensPerCall,
        int maxTotalTokensPerStep,
        Duration gatewayTotalDeadline,
        Duration inlineRetryMaxDelay,
        Duration perCallTimeout,
        int failureThreshold,
        Duration circuitCoolDown,
        Duration backoffBase,
        Duration backoffMax,
        String provider,
        String contractVersion
) {
    public ModelGatewayParams {
        if (maxCallRetries < 0) {
            throw new IllegalArgumentException("maxCallRetries 不能为负: " + maxCallRetries);
        }
        if (maxPhysicalCallsPerStep <= 0 || maxPromptTokensPerCall <= 0
                || maxCompletionTokensPerCall <= 0 || maxTotalTokensPerStep <= 0) {
            throw new IllegalArgumentException("预算四项必须为正");
        }
        Objects.requireNonNull(gatewayTotalDeadline, "gatewayTotalDeadline");
        Objects.requireNonNull(inlineRetryMaxDelay, "inlineRetryMaxDelay");
        Objects.requireNonNull(perCallTimeout, "perCallTimeout");
        Objects.requireNonNull(circuitCoolDown, "circuitCoolDown");
        Objects.requireNonNull(backoffBase, "backoffBase");
        Objects.requireNonNull(backoffMax, "backoffMax");
        if (gatewayTotalDeadline.isZero() || gatewayTotalDeadline.isNegative()
                || inlineRetryMaxDelay.isZero() || inlineRetryMaxDelay.isNegative()
                || perCallTimeout.isZero() || perCallTimeout.isNegative()
                || circuitCoolDown.isZero() || circuitCoolDown.isNegative()
                || backoffBase.isZero() || backoffBase.isNegative()
                || backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("时长旋钮必须为正且 backoffMax >= backoffBase");
        }
        if (perCallTimeout.compareTo(gatewayTotalDeadline) > 0) {
            throw new IllegalArgumentException("perCallTimeout 不得大于 gatewayTotalDeadline");
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold 必须为正: " + failureThreshold);
        }
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(contractVersion, "contractVersion");
    }

    /** 路由的 checkpoint 契约身份（§4.3/I30）：provider/requestedModel/contractVersion。 */
    public ModelRouteIdentity contractIdentityOf(ModelRoute route) {
        return new ModelRouteIdentity(provider, route.requestedModel(), contractVersion);
    }
}
