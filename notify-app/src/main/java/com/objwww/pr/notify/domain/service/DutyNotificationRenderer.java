package com.objwww.pr.notify.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 值班消息渲染器（M7-14）：duty payload（type/operation_id/source/severity/title/body）
 * → 值班通知卡片——不是 RCA 报告卡片（字段白名单独立，报告三元组不出现）。
 * 消毒面全复用父类（秘密遮蔽/控制字符/@all 中和/链接 scheme/双上限截断）；
 * operation_id 缺失 = 确定性拒绝（同报告纪律：重复投递可检测性优先）。
 */
public class DutyNotificationRenderer extends NotificationRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public DutyNotificationRenderer(int maxFieldChars, int maxTotalChars) {
        super(maxFieldChars, maxTotalChars);
    }

    @Override
    public RenderedNotification render(String payloadJson) {
        JsonNode payload;
        try {
            payload = JSON.readTree(Objects.requireNonNull(payloadJson, "payloadJson"));
        } catch (Exception e) {
            throw new RenderException("payload 不可解析: " + e.getMessage());
        }
        if (!payload.isObject() || payload.path("operation_id").asText("").isBlank()) {
            throw new RenderException("payload 缺 operation_id（重复可检测性破坏，拒绝渲染）");
        }
        UUID operationId = UUID.fromString(payload.get("operation_id").asText());
        String source = sanitize(payload.path("source").asText(""));
        String severity = sanitize(payload.path("severity").asText(""));
        String title = sanitize(payload.path("title").asText(""));
        String body = sanitize(payload.path("body").asText(""));

        String heading = truncate("【值班】" + (severity.isBlank() ? "" : "[" + severity + "] ")
                + title, 60);
        StringBuilder text = new StringBuilder();
        text.append("- **title**: ").append(truncate(title, maxFieldChars)).append('\n');
        if (!source.isBlank()) {
            text.append("- **source**: ").append(truncate(source, maxFieldChars)).append('\n');
        }
        if (!severity.isBlank()) {
            text.append("- **severity**: ").append(truncate(severity, maxFieldChars)).append('\n');
        }
        if (!body.isBlank()) {
            text.append("- **body**: ").append(truncate(body, maxFieldChars)).append('\n');
        }
        text.append("- operation_id: ").append(operationId).append('\n');
        return new RenderedNotification(heading, truncate(text.toString(), maxTotalChars),
                operationId);
    }
}
