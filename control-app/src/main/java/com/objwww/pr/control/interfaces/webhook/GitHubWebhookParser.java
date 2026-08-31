package com.objwww.pr.control.interfaces.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;

/**
 * GitHub webhook payload 解析（纯函数，可单测）。
 * M1-T03 两段式改造后，入口（HTTP 线程）只做 {@link #readEntryMeta} 一遍解析提取落库元数据
 * （畸形 JSON 不报错——payload_json 置 NULL 落库审计，E2E-22）；事件过滤与全量解析后移到
 * InboxProcessor（方案 §4.2）：六 action 之外 → IGNORED 留痕（ST-16），
 * 六 action 且字段不全 → {@link MalformedPayloadException}（载荷不可变，重试无义，死信）。
 */
public final class GitHubWebhookParser {

    public static final String EVENT_PULL_REQUEST = "pull_request";
    /** M1 六 action（方案 §4.4 决策表）：closed/converted_to_draft 的具体路由由 T06 接管 */
    public static final Set<String> HANDLED_ACTIONS = Set.of(
            "opened", "synchronize", "reopened",
            "ready_for_review", "converted_to_draft", "closed");

    /** 入口落库元数据（inbox 列：github_action / installation_id / repository_id）；三列均可空 */
    public record EntryMeta(String action, Long installationId, Long repositoryId) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 入口一遍解析（M1-T03 §4.2 HTTP 线程）：只提取落库元数据，不苛求字段完整；
     * JSON 本身非法 → MalformedPayloadException（调用方置 payload_json=NULL 落库，E2E-22）。
     */
    public EntryMeta readEntryMeta(byte[] payload) {
        JsonNode root = readTree(payload);
        JsonNode action = root.get("action");
        return new EntryMeta(
                action != null && action.isTextual() ? action.asText() : null,
                longOrNull(root.path("installation").path("id")),
                longOrNull(root.path("repository").path("id")));
    }

    private static Long longOrNull(JsonNode node) {
        return node.canConvertToLong() && !node.isTextual() ? node.asLong() : null;
    }

    /** 只读 action 字段；JSON 本身非法 → MalformedPayloadException */
    public String readAction(byte[] payload) {
        JsonNode root = readTree(payload);
        JsonNode action = root.get("action");
        return action != null && action.isTextual() ? action.asText() : null;
    }

    /** 本系统是否处理该事件（事件类型 + action 双过滤；InboxProcessor 据此路由 IGNORED，ST-16） */
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
                requiredText(base, "sha", "pull_request.base.sha"),
                instantOrNull(pr.get("updated_at"))); // M1-T05：LWW 快筛输入，缺失/非法 → null（EX-18）
    }

    /** ISO-8601 宽松解析：非文本/非法格式一律 null（不猜不补，调用方转权威读，EX-18） */
    static Instant instantOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
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
