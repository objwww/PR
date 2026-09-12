package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 证据包 JSON 编解码器（BA-22 去 Jackson 清账，R3 domain 零框架）：Jackson 只在本类
 * （application 层，NativeReportAdapter 同层同惯例）；向 domain 侧输出的永远是
 * <b>中立树</b>——{@code Map<String,Object>/List<String|Map|List>/String/Number/
 * Boolean/null}，domain 消费面零 Jackson 类型。
 *
 * <p>写出经 {@link InternalCanonicalJsonV1#canonicalize}（domain 合规件，只吃已解析
 * 结构）——字典序键序 + 数字归一；与旧 ObjectNode.toString() 的输入键序不同，键序
 * 漂移经前置检查确认无下游 digest 对账（raw/payload digest 只对原文）。
 */
public final class EvidencePackageJsonCodec {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private EvidencePackageJsonCodec() {
    }

    /** JSON 文本 → 中立树；解析失败抛 IllegalArgumentException（消息进拒绝原因链） */
    public static Object readTree(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return toNeutral(MAPPER.readTree(json));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 既有 JsonNode 持有方（eval/release 应用层）的中立树转换桥 */
    public static Map<String, Object> toMap(JsonNode node) {
        Objects.requireNonNull(node, "node");
        if (!(toNeutral(node) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) map;
        return out;
    }

    /** 中立树 → canonical JSON 文本（字典序键序 + 数字归一，InternalCanonicalJsonV1 契约） */
    public static String writeCanonical(Object neutral) {
        return com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1.canonicalize(neutral);
    }

    /** JsonNode → 中立树递归转换（容器→Map/List，标量按 Jackson 类型面直映） */
    static Object toNeutral(JsonNode node) {
        Objects.requireNonNull(node, "node");
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), toNeutral(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayList<Object> out = new ArrayList<>();
            node.forEach(item -> out.add(toNeutral(item)));
            return out;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        // 二进制/Pojo/POJO 兜底：证据包形状里不出现，出现即按文本值保守降级
        return node.asText();
    }
}
