package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.time.Instant;

/**
 * 模型调用故障的封闭分类（§4.2）。
 *
 * <p>每个实例携带 {@link FaultScope} 域归属，fallback 资格由域矩阵唯一判定（附录 A）。
 * 不携带供应商异常类型，不含堆栈以外的供应商概念。
 *
 * <p>sealed 保证新增故障类必须编译期显式处置（Router 决策无 else/default 兜底，AFT-20）。
 */
public sealed interface ModelCallFailure {

    FaultScope faultScope();

    /**
     * 超时（§4.2 A10/A11）：{@code remote=true} 是 HTTP 408（服务端明示，可同路由重试）；
     * {@code remote=false} 是本地 per-call-timeout/等待中断（分不清模型慢与端点慢，不原地重试）。
     */
    record Timeout(FaultScope faultScope, boolean remote) implements ModelCallFailure {
        public Timeout {
            if (faultScope == null) faultScope = FaultScope.ENDPOINT;
        }
    }

    /** 网络错误（DNS/连接拒绝/TLS/中途 reset） */
    record NetworkError(FaultScope faultScope) implements ModelCallFailure {
        public NetworkError {
            if (faultScope == null) faultScope = FaultScope.ENDPOINT;
        }
    }

    /** 协议错误（HTTP 200 但 JSON 损坏/body 截断） */
    record ProtocolError(FaultScope faultScope) implements ModelCallFailure {
        public ProtocolError {
            if (faultScope == null) faultScope = FaultScope.ENDPOINT;
        }
    }

    /** 限流（429 RateQuota/BurstRate 模型级；通用 Throttling 保守归账号级），可重试 */
    record RateLimitedTransient(FaultScope faultScope, Duration retryAfter) implements ModelCallFailure {
        public RateLimitedTransient {
            if (faultScope == null) faultScope = FaultScope.MODEL;
        }
    }

    /** 账号级配额暂时耗尽（429 AllocationQuota/insufficient_quota），需长等待 */
    record QuotaTemporary(FaultScope faultScope, Instant notBefore) implements ModelCallFailure {
        public QuotaTemporary {
            if (faultScope == null) faultScope = FaultScope.ACCOUNT;
        }
    }

    /** 免费额度彻底耗尽（403 AllocationQuota.FreeTierOnly） */
    record QuotaExhausted(FaultScope faultScope) implements ModelCallFailure {
        public QuotaExhausted {
            if (faultScope == null) faultScope = FaultScope.ACCOUNT;
        }
    }

    /** 账单/未开通（400 Arrearage/未开通族） */
    record BillingOrActivation(FaultScope faultScope) implements ModelCallFailure {
        public BillingOrActivation {
            if (faultScope == null) faultScope = FaultScope.ACCOUNT;
        }
    }

    /** 权限拒绝（401/403 Model.AccessDenied） */
    record AuthDenied(FaultScope faultScope) implements ModelCallFailure {
        public AuthDenied {
            if (faultScope == null) faultScope = FaultScope.CREDENTIAL;
        }
    }

    /** 请求无效（400 参数错/404 模型不存在/409/422/425） */
    record RequestInvalid(FaultScope faultScope) implements ModelCallFailure {
        public RequestInvalid {
            if (faultScope == null) faultScope = FaultScope.MODEL;
        }
    }

    /** 服务端错误（500/502/503/504） */
    record ServerError(FaultScope faultScope) implements ModelCallFailure {
        public ServerError {
            if (faultScope == null) faultScope = FaultScope.ENDPOINT;
        }
    }

    /** fail-closed 未知错误（SDK 未知异常/未知 error code/code 缺失） */
    record UnknownError(String reason) implements ModelCallFailure {
        @Override
        public FaultScope faultScope() {
            return null; // 无法确定域 → 按最保守处理
        }
    }
}
