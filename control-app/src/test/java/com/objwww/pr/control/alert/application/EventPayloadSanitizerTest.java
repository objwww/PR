package com.objwww.pr.control.alert.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE payload 白名单消毒件单测（M5-13；§17.9.3 红线——thought/原始 prompt/secret/
 * 完整工具参数永不进前端；口径对齐 notify-app NotificationRenderer：秘密值遮蔽 +
 * 截断 + 控制字符剥离）。
 */
class EventPayloadSanitizerTest {

    private final EventPayloadSanitizer sanitizer = new EventPayloadSanitizer(64);

    @Test
    void keepsOnlyWhitelistedFields() {
        Map<String, Object> out = sanitizer.sanitize("""
                {"summary":"指标完成","task_id":"t1","thought":"内部推理链","prompt":"原始提示词",
                 "tool_args":{"cmd":"rm -rf /"},"state":"DONE","digest":"abc","unknown_field":1}
                """);

        assertThat(out).containsOnlyKeys("summary", "task_id", "state", "digest");
        assertThat(out.get("summary")).isEqualTo("指标完成");
        assertThat(out.get("task_id")).isEqualTo("t1");
    }

    @Test
    void redactsSecretLikeValues() {
        Map<String, Object> out = sanitizer.sanitize(
                "{\"summary\":\"配置 sk-prod1234567890123 已生效\",\"attempt_count\":2}");

        assertThat(out.get("summary")).asString().contains("[REDACTED]").doesNotContain("sk-prod");
        assertThat(out.get("attempt_count")).isEqualTo(2);
    }

    @Test
    void truncatesLongStringValuesToLimit() {
        Map<String, Object> out = sanitizer.sanitize(
                "{\"summary\":\"" + "长".repeat(200) + "\"}");

        assertThat(((String) out.get("summary")).length()).isLessThanOrEqualTo(64);
    }

    @Test
    void stripsControlCharsFromValues() {
        Map<String, Object> out = sanitizer.sanitize(
                "{\"summary\":\"行一\\u0007行二\\n行三\"}");

        assertThat(out.get("summary")).asString().doesNotContain("\u0007").contains("\n");
    }

    @Test
    void unparsablePayloadYieldsEmptyMapNotError() {
        assertThat(sanitizer.sanitize("not-json")).isEmpty();
        assertThat(sanitizer.sanitize(null)).isEmpty();
        assertThat(sanitizer.sanitize("[1,2]")).isEmpty();
    }

    @Test
    void nestedObjectsAreDroppedNotFlattened() {
        // 白名单键若带对象值（如 summary 出现结构化 payload）→ 降为标量丢弃，防夹带
        Map<String, Object> out = sanitizer.sanitize(
                "{\"summary\":{\"inner\":\"完整工具参数\"},\"state\":\"DONE\"}");

        assertThat(out).containsOnlyKeys("state");
    }
}
