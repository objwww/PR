package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ConfigBundle（M5-09）：不可变配置束——prompt/规则/工具策略/模型路由/阈值/
 * policy_version 全量内容 + bundle digest（canonical content 的 sha256，键序无关）+
 * revision。INV-AM5-5：密钥不入 bundle（键名递归扫描 fail-closed）；
 * <b>本期不引入 OPA/WAITING_APPROVAL</b>（v1.1 裁定），仅保留策略版本字段
 * （{@link #POLICY_VERSION_KEY}，必填）。
 *
 * <p>digest 锚 = {@link InternalCanonicalJsonV1#canonicalize(Map)}（复用 AM4 规范化
 * JSON，键序无关），支撑"重跑 digest 一致"。content 深冻结：构造期递归不可变化拷贝，
 * 外部改写原映射不回流。
 *
 * <p>零框架（L0：release 域零框架规则，ControlArchitectureTest）。
 */
public record ConfigBundle(UUID id,
                           Digest bundleDigest,
                           long revision,
                           Map<String, Object> content,
                           String createdBy,
                           Instant createdAt) {

    /** 策略版本键（v1.1：bundle 仅保留策略版本字段；必填非 blank） */
    public static final String POLICY_VERSION_KEY = "policy_version";

    /** 密钥材料键名黑名单（与 ControlArchitectureTest AFT-28 同族语义；键名递归匹配） */
    private static final Pattern SECRET_KEY = Pattern.compile(
            ".*(apikey|api_key|authorization|bearer|secret|password|privatekey).*");

    public ConfigBundle {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(bundleDigest, "bundleDigest 不得为 null");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须 ≥ 1: " + revision);
        }
        content = freeze(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content 不得为空");
        }
        policyVersionOf(content);
        Objects.requireNonNull(createdBy, "createdBy 不得为 null");
        if (createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy 不得为 blank");
        }
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
    }

    /** 发布面构造：digest 由 canonical content 派生（唯一幂等锚） */
    public static ConfigBundle of(Map<String, Object> content, String createdBy,
                                  Instant createdAt) {
        Map<String, Object> frozen = freeze(content);
        return new ConfigBundle(UUID.randomUUID(),
                Digest.sha256Of(InternalCanonicalJsonV1.canonicalize(frozen)),
                1L, frozen, createdBy, createdAt);
    }

    /** 策略版本读取（必填字段，构造期已校验） */
    public String policyVersion() {
        return policyVersionOf(content);
    }

    private static String policyVersionOf(Map<String, Object> content) {
        Object version = content.get(POLICY_VERSION_KEY);
        if (!(version instanceof String policyVersion) || policyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "content 必须携带非 blank 的 " + POLICY_VERSION_KEY + "（v1.1：仅保留策略版本字段）");
        }
        return policyVersion;
    }

    /** 密钥材料键名递归扫描（INV-AM5-5 fail-closed；命中即拒绝发布） */
    private static Map<String, Object> freeze(Map<String, Object> content) {
        Objects.requireNonNull(content, "content 不得为 null");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "content 键不得为 null");
            if (SECRET_KEY.matcher(key.toLowerCase()).matches()) {
                throw new IllegalArgumentException(
                        "密钥材料不得入 bundle（INV-AM5-5）: 键 " + key);
            }
            copy.put(key, freezeValue(entry.getValue(), key));
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value, String key) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> nestedCopy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : nested.entrySet()) {
                String nestedKey = String.valueOf(e.getKey());
                if (SECRET_KEY.matcher(nestedKey.toLowerCase()).matches()) {
                    throw new IllegalArgumentException(
                            "密钥材料不得入 bundle（INV-AM5-5）: 键 " + nestedKey);
                }
                nestedCopy.put(nestedKey, freezeValue(e.getValue(), nestedKey));
            }
            return java.util.Collections.unmodifiableMap(nestedCopy);
        }
        if (value instanceof List<?> list) {
            List<Object> listCopy = new java.util.ArrayList<>();
            for (Object item : list) {
                listCopy.add(freezeValue(item, key));
            }
            return java.util.Collections.unmodifiableList(listCopy);
        }
        return value;
    }
}
