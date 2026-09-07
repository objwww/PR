package com.objwww.pr.control.alert.domain.tool;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 工具定义契约（AM4 M4-13）：name/version/schema/risk/timeout/resultLimit + schema_hash。
 *
 * <p>约束：schema_hash 输入 = canonical JSON（InternalCanonicalJsonV1，字段序无关）；
 * schema 默认 additionalProperties=false（缺省归一为显式 false，显式 true 直接拒绝——
 * 与 M4-15 未声明字段硬拒绝同一防线）；risk 缺省从严（null → R3）；
 * 风险等级只来自本地注册表声明，不信外部 annotation。
 * 非法定义构造即拒（fail-fast，启动期暴露）。
 */
public record ToolDefinition(
        String name,
        String version,
        Map<String, Object> schema,
        ToolRisk risk,
        long timeoutMillis,
        long resultLimitBytes) {

    private static final Set<String> ALLOWED_PROPERTY_TYPES =
            Set.of("string", "number", "integer", "boolean", "array", "object");

    public ToolDefinition {
        requireName(name);
        requireVersion(version);
        if (schema == null) {
            throw new IllegalArgumentException("schema 不得为 null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeout 必须为正，实际: " + timeoutMillis);
        }
        if (resultLimitBytes <= 0) {
            throw new IllegalArgumentException("resultLimit 必须为正，实际: " + resultLimitBytes);
        }
        schema = normalizeSchema(schema);
        risk = risk == null ? ToolRisk.R3 : risk; // 缺省从严
    }

    /** schema_hash：canonical JSON 的 sha256（只含 schema 内容，与 timeout/name 等无关） */
    public String schemaHash() {
        return InternalCanonicalJsonV1.sha256(schema);
    }

    /** 归一：拷贝顶层、缺省 additionalProperties=false、显式 true 拒绝，并校验最小结构面 */
    private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("schema.type 必须为 object");
        }
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> props) || props.isEmpty()) {
            throw new IllegalArgumentException("schema.properties 必须为非空映射");
        }
        for (Map.Entry<?, ?> e : props.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> prop)
                    || !(prop.get("type") instanceof String propType)
                    || !ALLOWED_PROPERTY_TYPES.contains(propType)) {
                throw new IllegalArgumentException(
                        "属性 " + e.getKey() + " 缺合法 type 声明（"
                                + ALLOWED_PROPERTY_TYPES + "）");
            }
        }
        TreeMap<String, Object> normalized = new TreeMap<>();
        Object additional = schema.get("additionalProperties");
        if (Boolean.TRUE.equals(additional)) {
            throw new IllegalArgumentException(
                    "additionalProperties=true 拒绝（未声明字段必须硬拒绝，M4-15）");
        }
        normalized.putAll(schema);
        normalized.put("additionalProperties", false); // 缺省即 false，显式化进 hash
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static void requireName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "工具名必须匹配 [a-z][a-z0-9._-]{0,63}，实际: " + name);
        }
    }

    private static void requireVersion(String version) {
        if (version == null || !version.matches("[0-9A-Za-z][A-Za-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException(
                    "工具版本必须匹配 [0-9A-Za-z][A-Za-z0-9._-]{0,31}，实际: " + version);
        }
    }
}
