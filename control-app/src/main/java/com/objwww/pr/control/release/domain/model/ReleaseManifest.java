package com.objwww.pr.control.release.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * release_manifest typed 段（EN-01，增强线方案 §8.2 数据契约）：发布组合 8 成员——
 * schema_version 固定锚（禁静默换 schema）、角色→Prompt 资产映射、Skill 允许集、
 * 工具 schema 集、模型路由/采样参数、RAG 语料/索引版本、上下文裁剪/摘要规则、
 * Harness 兼容范围。组合身份 = 所在 bundle 的 canonical digest（段参与内容，
 * 依赖变动即新组合身份；v1.2 等显示标签不参与身份）。
 *
 * <p>本层只锁形状（fail-closed，零框架 L0 规则）：段键缺席 → {@code empty}
 * （存量 bundle 兼容）；键在而形不合法 → 解析拒绝。引用是否存在（依赖闭包）
 * 归 ConfigBundleService 发布面（需查资产仓储），不在本层。
 */
public record ReleaseManifest(String schemaVersion,
                              Map<String, String> roles,
                              List<String> skills,
                              List<String> toolSchemas,
                              Map<String, Object> modelRouting,
                              Map<String, Object> ragCorpus,
                              Map<String, Object> contextRules,
                              List<String> harnessCompat) {

    /** schema 协商锚：唯一合法值（升级走新值+兼容评审，禁静默换 schema） */
    public static final String SCHEMA_VERSION = "release-manifest.v1";

    /** bundle content 中的段键 */
    public static final String CONTENT_KEY = "release_manifest";

    private static final Pattern ASSET_DIGEST = Pattern.compile("[0-9a-f]{64}");

    public ReleaseManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "release_manifest schema_version 必须为 " + SCHEMA_VERSION + ": " + schemaVersion);
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("release_manifest roles 必须为非空映射（角色→Prompt 资产）");
        }
        Map<String, String> frozenRoles = new LinkedHashMap<>();
        roles.forEach((role, digest) -> {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("release_manifest roles 键不得为 blank");
            }
            requireDigest(digest, "roles");
            frozenRoles.put(role, digest);
        });
        roles = Collections.unmodifiableMap(frozenRoles);
        skills = frozenDigestList(skills, "skills");
        toolSchemas = frozenDigestList(toolSchemas, "tool_schemas");
        modelRouting = frozenObjectMap(modelRouting, "model_routing");
        ragCorpus = frozenObjectMap(ragCorpus, "rag_corpus");
        contextRules = frozenObjectMap(contextRules, "context_rules");
        if (harnessCompat == null || harnessCompat.isEmpty()) {
            throw new IllegalArgumentException("release_manifest harness_compat 必须为非空列表");
        }
        for (String item : harnessCompat) {
            if (item == null || item.isBlank()) {
                throw new IllegalArgumentException("release_manifest harness_compat 元素不得为 blank");
            }
        }
        harnessCompat = List.copyOf(harnessCompat);
    }

    /**
     * bundle content 装载：无 {@link #CONTENT_KEY} 键 → empty（存量 bundle 兼容，
     * P09 新旧组合共存面）；键在 → {@link #fromRaw}（形不合法抛 IAE）。
     */
    public static Optional<ReleaseManifest> fromContent(Map<String, Object> content) {
        Objects.requireNonNull(content, "content 不得为 null");
        if (!content.containsKey(CONTENT_KEY)) {
            return Optional.empty();
        }
        return Optional.of(fromRaw(content.get(CONTENT_KEY)));
    }

    /** 段原始对象 → typed manifest（非映射/成员形不合法 → IAE；schema_version 先于成员解析） */
    public static ReleaseManifest fromRaw(Object raw) {
        if (!(raw instanceof Map<?, ?> section)) {
            throw new IllegalArgumentException("release_manifest 段必须为映射");
        }
        if (!SCHEMA_VERSION.equals(section.get("schema_version"))) {
            throw new IllegalArgumentException("release_manifest schema_version 必须为 "
                    + SCHEMA_VERSION + ": " + section.get("schema_version"));
        }
        return new ReleaseManifest(
                str(section.get("schema_version")),
                stringMap(section.get("roles"), "roles"),
                strings(section.get("skills"), "skills"),
                strings(section.get("tool_schemas"), "tool_schemas"),
                objectMap(section.get("model_routing"), "model_routing"),
                objectMap(section.get("rag_corpus"), "rag_corpus"),
                objectMap(section.get("context_rules"), "context_rules"),
                strings(section.get("harness_compat"), "harness_compat"));
    }

    // ------------------------------------------------------------------ 内部

    private static String str(Object raw) {
        return raw instanceof String s ? s : null;
    }

    private static Map<String, String> stringMap(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("release_manifest " + key + " 必须为映射");
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            parsed.put(String.valueOf(e.getKey()),
                    e.getValue() instanceof String s ? s : String.valueOf(e.getValue()));
        }
        return parsed;
    }

    private static List<String> strings(Object raw, String key) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("release_manifest " + key + " 必须为列表");
        }
        List<String> parsed = new ArrayList<>();
        for (Object item : list) {
            parsed.add(item instanceof String s ? s
                    : (item == null ? null : String.valueOf(item)));
        }
        return parsed;
    }

    private static Map<String, Object> objectMap(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("release_manifest " + key + " 必须为映射");
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            parsed.put(String.valueOf(e.getKey()), e.getValue());
        }
        return parsed;
    }

    /** 构造期对象段冻结（null 拒绝；值面深冻结归 bundle content 层） */
    private static Map<String, Object> frozenObjectMap(Map<String, Object> raw, String key) {
        if (raw == null) {
            throw new IllegalArgumentException("release_manifest " + key + " 必须为映射");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    private static List<String> frozenDigestList(List<String> raw, String key) {
        if (raw == null) {
            throw new IllegalArgumentException("release_manifest " + key + " 必须为列表");
        }
        for (String digest : raw) {
            requireDigest(digest, key);
        }
        return List.copyOf(raw);
    }

    private static void requireDigest(String value, String key) {
        if (value == null || !ASSET_DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "release_manifest " + key + " 引用必须为 64 位小写 hex 资产 digest: " + value);
        }
    }
}
