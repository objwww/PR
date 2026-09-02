package com.objwww.pr.control.infrastructure.model;

import org.springframework.http.HttpHeaders;

/**
 * 原始 HTTP 错误载体（F-17：分类只认原始 status/headers/body，不认 Spring AI 异常类型）。
 *
 * <p>由 {@link RawHttpErrorCapture} 在 RestClient 错误处理链最底层抛出，
 * 穿透 Spring AI 异常包装后由 {@link SpringAiRouteClient} 捕获还原。
 * body 截断持有（排障够用的最小面；完整原文不落日志/账本——§4.11）。
 */
public final class RawHttpErrorException extends RuntimeException {

    /** body 持有上限（防御：异常路径不拖垮内存） */
    static final int MAX_BODY_CHARS = 8192;

    private final int status;
    private final HttpHeaders headers;
    private final String body;

    public RawHttpErrorException(int status, HttpHeaders headers, String body) {
        super("HTTP " + status); // message 只含状态码，不含 body（防日志泄密）
        this.status = status;
        this.headers = headers;
        this.body = body == null ? null
                : body.substring(0, Math.min(body.length(), MAX_BODY_CHARS));
    }

    public int status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public String body() {
        return body;
    }
}
