package com.objwww.pr.duty.hook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gatus custom provider 事件（M7-17 解析契约，gatus-config.yml 已钉）：
 * status=TRIGGERED|RESOLVED、endpoint/group/target/description/errors 六字段。
 *
 * <p>容错解析（契约基线实测坑，见 gatus-config.yml 头注）：[RESULT_ERRORS] 原样注入
 * 不做 JSON 转义，连接类错误串自带引号（Get "http://…"）——firing body 可能非严格
 * JSON。先走严格 Jackson；失败降级正则逐字段提取（errors 视为不透明文本，含引号
 * 截断风险如实接受：截断的 errors 只影响文案，不影响 status/endpoint 判定）。
 */
public record GatusEvent(String status, String endpoint, String group, String target,
                         String description, String errors) {

    private static final Pattern FIELD = Pattern.compile(
            "\"(status|endpoint|group|target|description|errors)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** @return null=两者都解析不出 status/endpoint（不可判定事件） */
    public static GatusEvent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = null;
        String endpoint = null;
        String group = null;
        String target = null;
        String description = null;
        String errors = null;
        boolean strict = true;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            status = text(root, "status");
            endpoint = text(root, "endpoint");
            group = text(root, "group");
            target = text(root, "target");
            description = text(root, "description");
            errors = text(root, "errors");
        } catch (Exception e) {
            strict = false;
        }
        if (!strict || status == null) {
            // 降级：非严格 body（errors 带引号截断了 JSON）逐字段正则
            Matcher m = FIELD.matcher(raw);
            while (m.find()) {
                String value = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                switch (m.group(1)) {
                    case "status" -> status = value;
                    case "endpoint" -> endpoint = value;
                    case "group" -> group = value;
                    case "target" -> target = value;
                    case "description" -> description = value;
                    case "errors" -> errors = value;
                }
            }
        }
        if (status == null || endpoint == null) {
            return null;
        }
        return new GatusEvent(status, endpoint,
                group == null ? "" : group, target == null ? "" : target,
                description == null ? "" : description, errors == null ? "" : errors);
    }

    /** TRIGGERED→firing / RESOLVED→resolved（其余值原样透传，服务端 400 钉死） */
    public String eventStatus() {
        return "RESOLVED".equalsIgnoreCase(status) ? "resolved" : "firing";
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
