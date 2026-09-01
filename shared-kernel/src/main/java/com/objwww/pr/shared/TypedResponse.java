package com.objwww.pr.shared;

import java.util.List;
import java.util.Map;

/**
 * GitHub 统一响应（GitHubWriteAdapter 产出，Handler 消费）：HTTP 状态码 + 已解析 JSON。
 * 对象响应（check run / checks-for-sha）走 objectBody；数组响应（reviews 列表）走 arrayBody；
 * 两者至多其一非空。JSON 解析只发生在 adapter（infrastructure），Handler 保持纯翻译（I4）。
 */
public record TypedResponse(
        int status,
        Map<String, Object> objectBody,
        List<Map<String, Object>> arrayBody,
        Long retryAfterSeconds,
        Long rateLimitRemaining,
        Long rateLimitResetEpochSec) {

    public TypedResponse {
        if (objectBody != null && arrayBody != null) {
            throw new IllegalArgumentException("objectBody 与 arrayBody 至多其一非空");
        }
        if (objectBody != null) {
            objectBody = Map.copyOf(objectBody);
        }
        if (arrayBody != null) {
            arrayBody = List.copyOf(arrayBody);
        }
        if (retryAfterSeconds != null && retryAfterSeconds <= 0) {
            retryAfterSeconds = null;
        }
    }

    /** M0/M1 调用兼容构造；无响应头元数据。 */
    public TypedResponse(int status, Map<String, Object> objectBody,
                         List<Map<String, Object>> arrayBody) {
        this(status, objectBody, arrayBody, null, null, null);
    }

    /** 无响应体（如 404/422 只关心状态与少量错误字段时也可用 ofObject 带上 error body） */
    public static TypedResponse ofStatus(int status) {
        return new TypedResponse(status, null, null, null, null, null);
    }

    public static TypedResponse ofObject(int status, Map<String, Object> body) {
        return new TypedResponse(status, body, null, null, null, null);
    }

    public static TypedResponse ofArray(int status, List<Map<String, Object>> body) {
        return new TypedResponse(status, null, body, null, null, null);
    }

    public TypedResponse withRateLimitHeaders(Long retryAfter, Long remaining, Long resetEpochSec) {
        return new TypedResponse(status, objectBody, arrayBody, retryAfter, remaining, resetEpochSec);
    }

    public boolean isServerError() {
        return status >= 500 && status <= 599;
    }
}
