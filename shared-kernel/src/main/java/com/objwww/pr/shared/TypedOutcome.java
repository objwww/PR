package com.objwww.pr.shared;

import java.util.Objects;

/**
 * 写调用结果的统一归类（Handler.interpret 产出，FencedPublicationExecutor 据以走 T3-B）。
 * 与 §6.3/EX-01/02/03 及 L4 归类表一一对应。
 */
public record TypedOutcome(
        Kind kind,
        String remoteId,
        String remoteUrl,
        String errorCode,
        String errorDetail) {

    public enum Kind {
        /** 远端副作用确认存在（2xx） */
        CONFIRMED,
        /** GitHub 5xx：可重试，退避进 RETRY_WAIT（EX-01） */
        SERVER_RETRYABLE,
        /** 传输层失败（超时/连接断，响应丢失）：不确定窗口，进 RECONCILING 禁盲目重发（EX-03） */
        OUTCOME_UNKNOWN,
        /** 422 且属 head/commit 不匹配类：确定性否定 → SUPERSEDED（last_error_code=STALE_HEAD，EX-02） */
        STALE_HEAD_SUPERSEDED,
        /** 其他 422 等确定性失败 → FAILED_TERMINAL（EX-02） */
        FAILED_TERMINAL,
        /** 401/403：FAILED_TERMINAL + 告警事件（凭证失效须人工换钥，重试无意义） */
        AUTH_FAILED,
        /** 策略性人工介入（如 UPDATE_CHECK 远端 404，M0 不自动重建，§6.3） */
        MANUAL
    }

    public TypedOutcome {
        Objects.requireNonNull(kind, "kind");
    }

    public static TypedOutcome confirmed(String remoteId, String remoteUrl) {
        return new TypedOutcome(Kind.CONFIRMED,
                Objects.requireNonNull(remoteId, "remoteId"), remoteUrl, null, null);
    }

    public static TypedOutcome serverRetryable(String detail) {
        return new TypedOutcome(Kind.SERVER_RETRYABLE, null, null, "GITHUB_5XX", detail);
    }

    public static TypedOutcome outcomeUnknown(String detail) {
        return new TypedOutcome(Kind.OUTCOME_UNKNOWN, null, null, "TRANSPORT_LOST", detail);
    }

    public static TypedOutcome staleHead(String detail) {
        return new TypedOutcome(Kind.STALE_HEAD_SUPERSEDED, null, null, "STALE_HEAD", detail);
    }

    public static TypedOutcome failedTerminal(String errorCode, String detail) {
        return new TypedOutcome(Kind.FAILED_TERMINAL, null, null,
                Objects.requireNonNull(errorCode, "errorCode"), detail);
    }

    public static TypedOutcome authFailed(String detail) {
        return new TypedOutcome(Kind.AUTH_FAILED, null, null, "AUTH_FAILED", detail);
    }

    public static TypedOutcome manual(String errorCode, String detail) {
        return new TypedOutcome(Kind.MANUAL, null, null,
                Objects.requireNonNull(errorCode, "errorCode"), detail);
    }
}
