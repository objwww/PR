package com.objwww.pr.control.alert.domain.tool;

import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;

/**
 * 动作摘要的输入信封（AM4 M4-15 后半）：一次工具调用语义身份的全部字段。
 *
 * <p>ActionDigest 对 envelope 整体 canonicalize，不做手工分隔符拼接——
 * 字段名即命名空间，新增字段只会改变摘要而不会产生边界歧义。
 * args 是已解析结构（Map/List/String/Number/Boolean/null，见 InternalCanonicalJsonV1）；
 * investigationInputDigest 可为 null（该调用无调查输入绑定——EX-A0 F04 语义：
 * 值 = run 冻结的调查输入身份，不再是配置/快照 digest 的混用位）。
 */
public record ActionEnvelope(
        String toolNamespace,
        String toolName,
        String toolVersion,
        String schemaVersion,
        Object args,
        String timeRange,
        InvestigationInputDigest investigationInputDigest) {

    public ActionEnvelope {
        requireNonBlank(toolNamespace, "toolNamespace");
        requireNonBlank(toolName, "toolName");
        requireNonBlank(toolVersion, "toolVersion");
        requireNonBlank(schemaVersion, "schemaVersion");
        requireNonBlank(timeRange, "timeRange");
        // args 可为 null（无参调用）；investigationInputDigest 可为 null（无输入绑定）
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空/blank");
        }
    }
}
