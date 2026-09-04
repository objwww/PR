package com.objwww.pr.control.infrastructure.holmes;

/**
 * Holmes HTTP 错误分类（§6.5；M3 ProviderErrorClassifier 同构的纯函数）。
 *
 * <p>429=可重试（评审修正：Holmes 无 Retry-After 概念，退避由我方决策）；
 * 401/403=终态（凭证问题重试无意义）；5xx/超时/网络=可重试；
 * 其余 4xx=终态（请求本身非法）；未知=可重试（max_attempts 封顶兜底）。
 */
public final class HolmesErrorClassifier {

    public enum Kind { RETRYABLE, TERMINAL }

    public record Classified(Kind kind, String errorClass) {
        public boolean retryable() {
            return kind == Kind.RETRYABLE;
        }
    }

    private HolmesErrorClassifier() {
    }

    public static Classified classify(int httpStatus) {
        if (httpStatus == 429) {
            return new Classified(Kind.RETRYABLE, "HTTP_429_RATE_LIMITED");
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return new Classified(Kind.TERMINAL, "HTTP_AUTH_DENIED");
        }
        if (httpStatus == 400 || httpStatus == 404 || httpStatus == 405 || httpStatus == 422) {
            return new Classified(Kind.TERMINAL, "HTTP_REQUEST_INVALID");
        }
        if (httpStatus >= 500 && httpStatus <= 599) {
            return new Classified(Kind.RETRYABLE, "HTTP_SERVER_ERROR");
        }
        // 未知/非预期：按可重试收敛（有 max_attempts 封顶，不会无限循环）
        return new Classified(Kind.RETRYABLE, "HTTP_UNEXPECTED");
    }

    public static Classified timeout() {
        return new Classified(Kind.RETRYABLE, "TIMEOUT");
    }

    public static Classified networkError() {
        return new Classified(Kind.RETRYABLE, "NETWORK_ERROR");
    }
}
