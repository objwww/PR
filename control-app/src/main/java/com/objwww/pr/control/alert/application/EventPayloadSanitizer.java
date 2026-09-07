package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSE/事件 payload 白名单消毒件（M5-13；§17.9.3 红线——thought/原始 prompt/secret/
 * 完整工具参数永不进前端）。口径对齐 notify-app NotificationRenderer（M3-21）：
 * 白名单取字段、秘密值遮蔽 [REDACTED]、控制字符剥离、单字段截断。
 *
 * <p>纯函数、零触网；不可解析 payload → 空 map（读侧不因消毒阻断链路，与
 * JsonbText.decode 同姿态）。
 */
public final class EventPayloadSanitizer {

    /** 前端可见字段白名单（封闭集；新字段须过评审——防新增键夹带） */
    private static final Set<String> WHITELIST = Set.of(
            "summary", "state", "status", "reason_code", "reasonCode", "task_id", "taskId",
            "task_key", "taskKey", "attempt", "attempt_count", "attemptCount", "digest",
            "schema_version", "generation", "seq", "event_id", "verdict", "count",
            "timeout_ms", "duration_ms", "progress", "done", "total", "worker", "epoch");

    /** 禁入键（双保险：即便误入白名单也拒绝——红线键显式封死） */
    private static final Set<String> FORBIDDEN = Set.of(
            "thought", "thoughts", "prompt", "raw_prompt", "tool_args", "toolArgs",
            "args", "params", "arguments", "input", "output", "raw", "payload_raw",
            "api_key", "apiKey", "token", "secret", "password", "authorization");

    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(sk-[A-Za-z0-9_-]{8,}|Bearer\\s+[A-Za-z0-9._-]{8,}|"
                    + "(?i)(api[_-]?key|secret|token|password)[\"'\\s:=:]{1,4}[A-Za-z0-9._-]{8,})");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");
    private static final String REDACTED = "[REDACTED]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int maxFieldChars;

    public EventPayloadSanitizer(int maxFieldChars) {
        if (maxFieldChars < 1) {
            throw new IllegalArgumentException("maxFieldChars 必须 >= 1");
        }
        this.maxFieldChars = maxFieldChars;
    }

    /** payload JSON → 白名单标量投影（禁入键/嵌套对象一律丢弃） */
    public Map<String, Object> sanitize(String payloadJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (payloadJson == null) {
            return out;
        }
        Map<?, ?> payload;
        try {
            Object parsed = JSON.readValue(payloadJson, Object.class);
            if (!(parsed instanceof Map)) {
                return out;
            }
            payload = (Map<?, ?>) parsed;
        } catch (Exception e) {
            return out;
        }
        for (Map.Entry<?, ?> entry : payload.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!WHITELIST.contains(key) || FORBIDDEN.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String s) {
                out.put(key, redact(strip(s)));
            } else if (value instanceof Number || value instanceof Boolean) {
                out.put(key, value);
            }
            // 嵌套对象/数组一律丢弃（白名单键携带结构化值 = 夹带面）
        }
        return out;
    }

    private String redact(String value) {
        return truncate(SECRET_LIKE.matcher(value).replaceAll(REDACTED));
    }

    private String truncate(String value) {
        return value.length() <= maxFieldChars ? value : value.substring(0, maxFieldChars);
    }

    private String strip(String value) {
        return CONTROL_CHARS.matcher(value).replaceAll("");
    }
}
