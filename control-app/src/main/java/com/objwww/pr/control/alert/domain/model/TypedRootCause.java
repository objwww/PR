package com.objwww.pr.control.alert.domain.model;

import java.util.Objects;

/**
 * EvidencePackage v2 类型化根因（AM3 §6.3；M3-01）：v1 自由文本 root_cause 的类型化升级。
 *
 * <p>三字段全必填非空——评分器"枚举等值 + 版本化同义词表"（§6.4 root_cause_hit）
 * 的输入前提是可判定根因；任一字段缺失/blank 即结构失败（REJECTED_SCHEMA_MISMATCH）。
 * component/fault_type/reason_code 是开放集文本（同义词表在评分侧做版本化归一），
 * 这里只管长度上限与非空。
 */
public record TypedRootCause(String component, String faultType, String reasonCode) {

    public static final int MAX_COMPONENT_CHARS = 128;
    public static final int MAX_FAULT_TYPE_CHARS = 64;
    public static final int MAX_REASON_CODE_CHARS = 128;

    public TypedRootCause {
        component = requireBounded(component, "component", MAX_COMPONENT_CHARS);
        faultType = requireBounded(faultType, "fault_type", MAX_FAULT_TYPE_CHARS);
        reasonCode = requireBounded(reasonCode, "reason_code", MAX_REASON_CODE_CHARS);
    }

    static String requireBounded(String value, String field, int maxChars) {
        Objects.requireNonNull(value, field + " 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(field + " 超长: " + value.length() + " > " + maxChars);
        }
        return value;
    }
}
