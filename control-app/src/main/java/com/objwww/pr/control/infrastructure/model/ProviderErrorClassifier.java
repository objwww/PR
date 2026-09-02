package com.objwww.pr.control.infrastructure.model;

import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelCallFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * HTTP status × provider error code 二维分类（§4.2）：百炼官方错误码映射。
 *
 * <p>F-17 实证：Spring AI 1.0.0 把所有 4xx 归 NonTransientAiException，粒度不足——
 * 分类输入只认原始 HTTP status/headers/body，不依赖 Spring AI 异常类型。
 */
public final class ProviderErrorClassifier {

    /**
     * HTTP status 初分 + error code 细分（§4.2 两表）。
     *
     * @param httpStatus HTTP 状态码
     * @param errorCode  百炼 error.code（可为 null）
     * @param retryAfter Retry-After 解析结果（可为 null）
     * @return 故障分类
     */
    public ModelCallFailure classify(int httpStatus, String errorCode, Duration retryAfter) {
        // 第一步：HTTP 状态初分
        return switch (httpStatus) {
            case 400 -> classify400(errorCode);
            case 401 -> new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL);
            case 403 -> classify403(errorCode);
            case 404, 409, 422, 425 -> new ModelCallFailure.RequestInvalid(FaultScope.MODEL);
            case 408 -> new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true); // A10：服务端明示超时，可同路由重试
            case 429 -> classify429(errorCode, retryAfter);
            case 500, 502, 503, 504 -> classifyServerError(errorCode);
            default -> {
                if (httpStatus >= 400 && httpStatus < 500) {
                    // 其他 4xx：保守归 RequestInvalid
                    yield new ModelCallFailure.RequestInvalid(FaultScope.MODEL);
                }
                if (httpStatus >= 500) {
                    // 其他 5xx：保守归 ServerError
                    yield new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
                }
                // 非标准状态码：fail-closed
                yield new ModelCallFailure.UnknownError("HTTP " + httpStatus);
            }
        };
    }

    /**
     * 400 细分（§4.2）：欠费/未开通 vs 参数错误。
     */
    private ModelCallFailure classify400(String errorCode) {
        if (errorCode == null) {
            return new ModelCallFailure.RequestInvalid(FaultScope.MODEL);
        }

        // F-11 实证：欠费/未开通是 400 而非 429
        if (errorCode.contains("Arrearage") || errorCode.contains("NotActivated")
                || errorCode.contains("ProductNotActivated")) {
            return new ModelCallFailure.BillingOrActivation(FaultScope.ACCOUNT);
        }

        // 其他 400：参数错误
        return new ModelCallFailure.RequestInvalid(FaultScope.MODEL);
    }

    /**
     * 403 细分（§4.2）：免费额度耗尽 vs 权限拒绝。
     */
    private ModelCallFailure classify403(String errorCode) {
        if (errorCode == null) {
            // 无法识别时保守按 AuthDenied
            return new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL);
        }

        // F-11 实证：免费额度耗尽是 403
        if (errorCode.contains("FreeTierOnly") || errorCode.contains("AllocationQuota")) {
            return new ModelCallFailure.QuotaExhausted(FaultScope.ACCOUNT);
        }

        if (errorCode.contains("AccessDenied")) {
            return new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL);
        }

        // 其他 403：保守按 AuthDenied
        return new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL);
    }

    /**
     * 429 细分（§4.2）：模型级 RateQuota / 并发 BurstRate / 配额 AllocationQuota / 通用 Throttling。
     */
    private ModelCallFailure classify429(String errorCode, Duration retryAfter) {
        if (errorCode == null) {
            // 通用 429：保守归账号级
            return new ModelCallFailure.RateLimitedTransient(FaultScope.ACCOUNT, retryAfter);
        }

        // 模型级 RPM/RPS
        if (errorCode.contains("RateQuota") || errorCode.contains("limit_requests")) {
            return new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, retryAfter);
        }

        // 流量增速限流
        if (errorCode.contains("BurstRate")) {
            return new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, retryAfter);
        }

        // TPM/TPS 配额暂时耗尽（需长等待）
        if (errorCode.contains("AllocationQuota") || errorCode.contains("insufficient_quota")) {
            Instant notBefore = Instant.now().plus(retryAfter != null ? retryAfter : Duration.ofSeconds(60));
            return new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, notBefore);
        }

        // 通用 Throttling：保守归账号级
        if (errorCode.contains("Throttling")) {
            return new ModelCallFailure.RateLimitedTransient(FaultScope.ACCOUNT, retryAfter);
        }

        // 未知 429 code：fail-closed
        return new ModelCallFailure.UnknownError("429:" + errorCode);
    }

    /**
     * 5xx 细分：code 明示模型级则 MODEL，否则默认 ENDPOINT。
     */
    private ModelCallFailure classifyServerError(String errorCode) {
        // 当前无已知模型级 5xx code，默认全部 ENDPOINT
        return new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
    }

    /**
     * 网络错误分类（DNS/连接拒绝/TLS/中途 reset）。
     */
    public ModelCallFailure classifyNetworkError(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ModelCallFailure.NetworkError(FaultScope.ENDPOINT);
    }

    /**
     * 协议错误（HTTP 200 但 JSON 损坏/body 截断）。
     */
    public ModelCallFailure classifyProtocolError(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ModelCallFailure.ProtocolError(FaultScope.ENDPOINT);
    }

    /**
     * 本地超时（per-call-timeout 或等待被中断）——A11：不原地立即重试。
     */
    public ModelCallFailure classifyTimeout() {
        return new ModelCallFailure.Timeout(FaultScope.ENDPOINT, false);
    }

    /**
     * fail-closed 未知错误。
     */
    public ModelCallFailure classifyUnknown(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ModelCallFailure.UnknownError(reason);
    }
}
