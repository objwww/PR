package com.objwww.pr.control.alert.domain.tool;

import com.objwww.pr.shared.Digests;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作摘要（AM4 M4-15 后半）：sha256(canonicalize(envelope)) 纯函数。
 *
 * <p>对结构化 envelope 整体 canonicalize（字段：toolNamespace/toolName/toolVersion/
 * schemaVersion/canonicalArgs/timeRange/inputSnapshotDigest/canonicalizationVersion），
 * 不再用 '|' 手工拼接——字段名即命名空间，天然无边界歧义。
 * canonicalizationVersion 固定为 InternalCanonicalJsonV1.VERSION（"internal-v1"），
 * 规范化算法换版时摘要必然全体变化，防止跨版本误判"同一动作"。
 * 字段序无关由 InternalCanonicalJsonV1 保证——同一 envelope 必然同一 digest，
 * 是 ToolGateway 去重/回放匹配的锚点。
 */
public final class ActionDigest {

    private ActionDigest() {
    }

    public static String of(ActionEnvelope envelope) {
        Map<String, Object> canonicalForm = new LinkedHashMap<>();
        canonicalForm.put("toolNamespace", envelope.toolNamespace());
        canonicalForm.put("toolName", envelope.toolName());
        canonicalForm.put("toolVersion", envelope.toolVersion());
        canonicalForm.put("schemaVersion", envelope.schemaVersion());
        canonicalForm.put("canonicalArgs", envelope.args());
        canonicalForm.put("timeRange", envelope.timeRange());
        canonicalForm.put("inputSnapshotDigest", envelope.inputSnapshotDigest());
        canonicalForm.put("canonicalizationVersion", InternalCanonicalJsonV1.VERSION);
        return Digests.sha256Hex(InternalCanonicalJsonV1.canonicalize(canonicalForm));
    }
}
