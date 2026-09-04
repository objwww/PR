package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * payload_raw → ParsedAlert 列表（投影期拆组）。
 *
 * <p>入口（AlertIntakeService）已做全量结构校验后原样存 bytea，此处按同一协议重新解析。
 * 任何解析失败都抛 {@link IllegalArgumentException}——上游要么是 AM 协议漂移要么是行内
 * 载荷腐坏，重试无意义，调用方（AlertInboxProcessor）把整组送 DEAD_LETTER 审计。
 */
public final class AlertPayloadParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertPayloadParser() {
    }

    public static List<ParsedAlert> parse(byte[] payloadRaw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(payloadRaw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("payload_raw JSON 解析失败: " + e.getMessage(), e);
        }
        JsonNode alerts = root.path("alerts");
        if (!alerts.isArray()) {
            throw new IllegalArgumentException("payload_raw 缺少 alerts 数组");
        }
        List<ParsedAlert> out = new ArrayList<>(alerts.size());
        for (JsonNode alert : alerts) {
            out.add(parseOne(alert));
        }
        return out;
    }

    private static ParsedAlert parseOne(JsonNode alert) {
        if (!alert.isObject()) {
            throw new IllegalArgumentException("alerts 条目必须是 JSON 对象");
        }
        String statusRaw = text(alert, "status");
        AlertFiringStatus status;
        try {
            status = AlertFiringStatus.fromRaw(statusRaw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("alert.status 非法: " + statusRaw);
        }
        Instant startsAt = instant(alert, "startsAt");
        Instant endsAt = null;
        if (alert.hasNonNull("endsAt")) {
            try {
                endsAt = Instant.parse(alert.get("endsAt").asText());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("endsAt 非法");
            }
        }
        return new ParsedAlert(status, text(alert, "fingerprint"),
                stringMap(alert.path("labels")), stringMap(alert.path("annotations")),
                startsAt, endsAt);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException("缺少/非法字段: " + field);
        }
        return v.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 非法");
        }
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("标签字段必须是对象");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) {
                throw new IllegalArgumentException("标签值必须是字符串: " + e.getKey());
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }
}
