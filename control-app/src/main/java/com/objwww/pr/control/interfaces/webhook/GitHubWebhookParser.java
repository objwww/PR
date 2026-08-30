package com.objwww.pr.control.interfaces.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * GitHub webhook payload 解析（纯函数，可单测）。
 * 两段式：先读 action 做事件过滤（不需处理的 action 不苛求字段完整），
 * 对要处理的 pull_request opened/synchronize/reopened 再做全量字段提取——缺必需字段即
 * {@link MalformedPayloadException}（400，EX-08：畸形 payload 不入库不建 Run）。
 */
public final class GitHubWebhookParser {

    public static final String EVENT_PULL_REQUEST = "pull_request";
    public static final Set<String> HANDLED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 只读 action 字段；JSON 本身非法 → MalformedPayloadException */
    public String readAction(byte[] payload) {
        JsonNode root = readTree(payload);
        JsonNode action = root.get("action");
        return action != null && action.isTextual() ? action.asText() : null;
    }

    /** 本系统是否处理该事件（事件类型 + action 双过滤；其余 200 忽略） */
    public boolean isHandled(String eventType, String action) {
        return EVENT_PULL_REQUEST.equals(eventType) && action != null && HANDLED_ACTIONS.contains(action);
    }

    /** 全量解析 pull_request 事件；缺必需字段 → MalformedPayloadException */
    public PullRequestEvent parsePullRequest(byte[] payload, String deliveryId, String action) {
        JsonNode root = readTree(payload);
        JsonNode pr = required(root, "pull_request");
        JsonNode installation = required(root, "installation");
        JsonNode repository = required(root, "repository");
        JsonNode head = required(pr, "head");
        JsonNode base = required(pr, "base");

        if (deliveryId == null || deliveryId.isBlank()) {
            throw new MalformedPayloadException("缺 X-GitHub-Delivery 头");
        }
        return new PullRequestEvent(
                deliveryId,
                action,
                requiredLong(installation, "installation.id"),
                requiredLong(repository, "repository.id"),
                requiredText(repository, "full_name", "repository.full_name"),
                (int) requiredLong(root, "number"),
                requiredText(pr, "state", "pull_request.state"),
                pr.path("draft").asBoolean(false),
                pr.path("merged").asBoolean(false),
                requiredText(head, "sha", "pull_request.head.sha"),
                requiredText(base, "ref", "pull_request.base.ref"),
                requiredText(base, "sha", "pull_request.base.sha"));
    }

    private JsonNode readTree(byte[] payload) {
        try {
            return mapper.readTree(payload);
        } catch (Exception e) {
            throw new MalformedPayloadException("payload 不是合法 JSON", e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isObject()) {
            throw new MalformedPayloadException("payload 缺对象字段: " + field);
        }
        return child;
    }

    private static long requiredLong(JsonNode node, String field) {
        String leaf = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
        JsonNode v = node.get(leaf);
        if (v == null || !v.canConvertToLong() || v.isTextual()) {
            throw new MalformedPayloadException("payload 缺数值字段: " + field);
        }
        return v.asLong();
    }

    private static String requiredText(JsonNode node, String field, String label) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new MalformedPayloadException("payload 缺文本字段: " + label);
        }
        return v.asText();
    }
}
