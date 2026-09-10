package com.objwww.pr.control.alert.domain.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数校验（AM4 M4-15 前半，GX-4 评审收紧）：additionalProperties=false 的硬执行——
 * <b>未声明字段直接拒绝</b>（禁静默裁字段：两个不同请求裁掉不同字段后可能撞 digest）；
 * required 缺失拒绝；声明字段类型不匹配拒绝。校验通过后调用方才 canonicalize + digest
 * （顺序即纪律：schema 校验成功 → canonicalize → digest）。
 *
 * <p>仅版本化规则明确列入的非语义元数据可豁免——当前豁免集为空（canonicalizationVersion
 * 已在 ActionDigest envelope 内预置换版锚点）；在引入真实噪声字段前不实现过滤机制。
 */
public final class ToolArgsValidator {

    private ToolArgsValidator() {
    }

    /** args=null 视同空参（无参调用仍受 required 约束） */
    public static void validate(ToolDefinition definition, Map<String, Object> args) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) definition.schema().get("properties");
        @SuppressWarnings("unchecked")
        List<String> required =
                (List<String>) definition.schema().getOrDefault("required", List.of());
        Map<String, Object> present = args == null ? Map.of() : args;

        for (String key : required) {
            if (!present.containsKey(key)) {
                throw new IllegalArgumentException(
                        "INVALID_ARGS: required 参数缺失: " + key);
            }
        }
        for (Map.Entry<String, Object> e : present.entrySet()) {
            String key = e.getKey();
            Object expected = properties.get(key);
            if (expected == null) {
                throw new IllegalArgumentException(
                        "INVALID_ARGS: 未声明参数（additionalProperties=false 硬拒绝）: " + key);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> constraint = (Map<String, Object>) expected;
            String expectedType = constraint.get("type").toString();
            if (!typeMatches(expectedType, e.getValue())) {
                throw new IllegalArgumentException(
                        "INVALID_ARGS: 参数 " + key + " 类型应为 " + expectedType);
            }
            // EX-A4a（F17）：schema 关键字收紧——maxLength/pattern（string 形状约束）
            if (e.getValue() instanceof String s) {
                Object maxLength = constraint.get("maxLength");
                if (maxLength instanceof Number cap && s.length() > cap.longValue()) {
                    throw new IllegalArgumentException(
                            "INVALID_ARGS: 参数 " + key + " 超过 maxLength=" + cap);
                }
                Object pattern = constraint.get("pattern");
                if (pattern instanceof String regex && !s.matches(regex)) {
                    throw new IllegalArgumentException(
                            "INVALID_ARGS: 参数 " + key + " 不匹配 pattern=" + regex);
                }
            }
        }
    }

    private static boolean typeMatches(String expectedType, Object value) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> false; // ToolDefinition 已冻结 type 集，防御分支
        };
    }
}
