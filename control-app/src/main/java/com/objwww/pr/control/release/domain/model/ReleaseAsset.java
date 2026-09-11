package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布资产（EN-01，增强线方案 §8.2）：Prompt/Skill/工具 schema 三类不可变资产的
 * 统一形态。身份 = 内容 canonical digest（内容寻址：同内容重发幂等、一字之差即
 * 新身份——S10"旧证明不能背书新内容"的域面锚）；asset_id/display_version 等
 * 仅作 content 内显示标签，不参与身份。密钥材料键名 fail-closed（INV-AM5-5，
 * 检测与 ConfigBundle 同源 {@link SecretKeys}）。
 *
 * <p>形状契约：PROMPT 模板占位符 ⊆ variables_schema（P02 缺变量注册即拒）；
 * SKILL 最小形状 = body + manifest（selector/步骤/预算等语义校验归 EN-08）；
 * TOOL_SCHEMA = schema 本体。parent_digest（血缘）可选，在场必须合法 digest。
 *
 * <p>零框架（L0：release 域零框架规则，ControlArchitectureTest）。
 */
public record ReleaseAsset(String kind,
                           Digest assetDigest,
                           Map<String, Object> content,
                           String createdBy,
                           Instant createdAt) {

    public static final String KIND_PROMPT = "PROMPT";
    public static final String KIND_SKILL = "SKILL";
    public static final String KIND_TOOL_SCHEMA = "TOOL_SCHEMA";
    /** EN-07 固定语料：runbook 文档正文（加法扩 kind，EN-01 三类同律） */
    public static final String KIND_RUNBOOK_DOC = "RUNBOOK_DOC";
    /** EN-07 固定语料：目录快照——登记 runbook_id→doc_digest + 有效期窗（R07 固定锚） */
    public static final String KIND_RUNBOOK_CATALOG = "RUNBOOK_CATALOG";

    private static final Set<String> KINDS =
            Set.of(KIND_PROMPT, KIND_SKILL, KIND_TOOL_SCHEMA,
                    KIND_RUNBOOK_DOC, KIND_RUNBOOK_CATALOG);

    private static final Pattern ASSET_DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** 模板占位符：{{var}}（花括号内任意非花括号串，trim 后比对声明集） */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{([^{}]+)}}");

    public ReleaseAsset {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知资产 kind: " + kind);
        }
        Objects.requireNonNull(assetDigest, "assetDigest 不得为 null");
        content = freeze(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content 不得为空");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy 不得为 blank");
        }
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
        validateShape(kind, content);
    }

    /** 注册面构造：digest 由 canonical content 派生（唯一幂等锚） */
    public static ReleaseAsset of(String kind, Map<String, Object> content,
            String createdBy, Instant createdAt) {
        Map<String, Object> frozen = freeze(content);
        return new ReleaseAsset(kind,
                Digest.sha256Of(InternalCanonicalJsonV1.canonicalize(frozen)),
                frozen, createdBy, createdAt);
    }

    // ------------------------------------------------------------------ 内部

    /** 密钥材料键名递归扫描（INV-AM5-5 fail-closed；命中即拒绝注册）+ 深冻结 */
    private static Map<String, Object> freeze(Map<String, Object> content) {
        Objects.requireNonNull(content, "content 不得为 null");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "content 键不得为 null");
            requireCleanKey(key);
            copy.put(key, freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> nestedCopy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : nested.entrySet()) {
                String nestedKey = String.valueOf(e.getKey());
                requireCleanKey(nestedKey);
                nestedCopy.put(nestedKey, freezeValue(e.getValue()));
            }
            return Collections.unmodifiableMap(nestedCopy);
        }
        if (value instanceof List<?> list) {
            List<Object> listCopy = new java.util.ArrayList<>();
            for (Object item : list) {
                listCopy.add(freezeValue(item));
            }
            return Collections.unmodifiableList(listCopy);
        }
        return value;
    }

    private static void requireCleanKey(String key) {
        if (SecretKeys.isSecretKey(key)) {
            throw new IllegalArgumentException("密钥材料不得入资产（INV-AM5-5）: 键 " + key);
        }
    }

    private static void validateShape(String kind, Map<String, Object> content) {
        Object parent = content.get("parent_digest");
        if (parent != null && !(parent instanceof String p
                && ASSET_DIGEST.matcher(p).matches())) {
            throw new IllegalArgumentException(
                    "parent_digest 必须为 64 位小写 hex 或缺席: " + parent);
        }
        switch (kind) {
            case KIND_PROMPT -> validatePrompt(content);
            case KIND_SKILL -> validateSkill(content);
            case KIND_TOOL_SCHEMA -> validateToolSchema(content);
            case KIND_RUNBOOK_DOC -> validateRunbookDoc(content);
            case KIND_RUNBOOK_CATALOG -> validateRunbookCatalog(content);
            default -> throw new IllegalArgumentException("未知资产 kind: " + kind);
        }
    }

    /** EN-07：runbook 文档最小形状 = runbook_id + title/description（模型匹配面）+ text */
    private static void validateRunbookDoc(Map<String, Object> content) {
        nonBlankString(content.get("runbook_id"), "runbook_id");
        nonBlankString(content.get("title"), "title");
        nonBlankString(content.get("description"), "description");
        nonBlankString(content.get("text"), "text");
    }

    /** EN-07：目录快照 = 非空 documents 列表，每条 runbook_id + 64 位 hex doc_digest
     * （R12 完整性锚：取文按登记 digest 精确解析，不存在 latest 可回退） */
    private static void validateRunbookCatalog(Map<String, Object> content) {
        if (!(content.get("documents") instanceof List<?> documents) || documents.isEmpty()) {
            throw new IllegalArgumentException("RUNBOOK_CATALOG documents 必须为非空列表");
        }
        for (Object item : documents) {
            if (!(item instanceof Map<?, ?> entry) || entry.isEmpty()) {
                throw new IllegalArgumentException(
                        "RUNBOOK_CATALOG documents 元素必须为非空映射");
            }
            Object runbookId = entry.get("runbook_id");
            if (!(runbookId instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException(
                        "RUNBOOK_CATALOG 条目 runbook_id 必须为非 blank 字符串");
            }
            Object docDigest = entry.get("doc_digest");
            if (!(docDigest instanceof String digest
                    && ASSET_DIGEST.matcher(digest).matches())) {
                throw new IllegalArgumentException(
                        "RUNBOOK_CATALOG 条目 doc_digest 必须为 64 位小写 hex: " + docDigest);
            }
        }
    }

    /** P02：模板占位符必须全部在 variables_schema 声明（缺变量注册即拒；多声明不拒） */
    private static void validatePrompt(Map<String, Object> content) {
        String template = nonBlankString(content.get("messages_template"),
                "messages_template");
        Object rawVars = content.get("variables_schema");
        if (!(rawVars instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("PROMPT variables_schema 必须为非空列表");
        }
        Set<String> declared = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String v) || v.isBlank()) {
                throw new IllegalArgumentException(
                        "PROMPT variables_schema 元素必须为非 blank 字符串");
            }
            declared.add(v);
        }
        Matcher matcher = TEMPLATE_VAR.matcher(template);
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            if (!declared.contains(variable)) {
                throw new IllegalArgumentException(
                        "PROMPT 模板占位符未在 variables_schema 声明（P02 缺变量）: " + variable);
            }
        }
    }

    /** SKILL 最小形状：body + 非空 manifest；resources（可选）必须为合法 digest 列表 */
    private static void validateSkill(Map<String, Object> content) {
        nonBlankString(content.get("body"), "body");
        if (!(content.get("manifest") instanceof Map<?, ?> manifest) || manifest.isEmpty()) {
            throw new IllegalArgumentException("SKILL manifest 必须为非空映射");
        }
        Object resources = content.get("resources");
        if (resources == null) {
            return;
        }
        if (!(resources instanceof List<?> list)) {
            throw new IllegalArgumentException("SKILL resources 必须为列表");
        }
        for (Object item : list) {
            if (!(item instanceof String digest && ASSET_DIGEST.matcher(digest).matches())) {
                throw new IllegalArgumentException(
                        "SKILL resources 必须为 64 位小写 hex digest 列表: " + item);
            }
        }
    }

    private static void validateToolSchema(Map<String, Object> content) {
        if (!(content.get("schema") instanceof Map<?, ?> schema) || schema.isEmpty()) {
            throw new IllegalArgumentException("TOOL_SCHEMA schema 必须为非空映射");
        }
    }

    private static String nonBlankString(Object raw, String key) {
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(key + " 必须为非 blank 字符串");
    }
}
