package com.objwww.pr.control.alert.domain.evidence;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;
import com.objwww.pr.shared.Digests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EvidenceSnapshot Builder（AM4 M4-20）：snapshot_digest = 成员 (type,payload_digest)
 * 按 digest 排序 canonical 后再哈希——<b>不依赖 id 列</b>（同事实重复摄取换 id 仍同
 * digest），成员顺序无关；observed_generation/config_digest/tool_registry_digest 参与
 * 输入——相同证据但代际/配置/工具注册表变化 → digest 必变。
 *
 * <p>防篡改基线 = 行摘要（EvidenceEnvelope.rowDigest）+ 快照聚合摘要（本件）+
 * DB 权限隔离；<b>不引入 CRC/prev_digest 行链</b>（评审裁定：同权可改 payload+digest
 * 时无强防篡改收益）。
 */
public final class EvidenceSnapshotBuilder {

    public static final String SNAPSHOT_SCHEMA_VERSION = "am4-snapshot.v1";

    /** 快照成员（内容身份二元组；evidence_id 不参与 digest） */
    public record Member(String evidenceType, String payloadDigest) {
    }

    /** 快照输入：代际 + 配置摘要 + 工具注册表摘要 + 成员集 */
    public record SnapshotInput(long observedGeneration, String configDigest,
            String toolRegistryDigest, List<Member> members) {

        public SnapshotInput {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public static String digest(SnapshotInput input) {
        List<Map<String, Object>> sortedMembers = new ArrayList<>();
        input.members().stream()
                .sorted(Comparator.comparing(Member::payloadDigest)
                        .thenComparing(Member::evidenceType))
                .forEach(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("evidenceType", m.evidenceType());
                    entry.put("payloadDigest", m.payloadDigest());
                    sortedMembers.add(entry);
                });
        Map<String, Object> canonicalForm = new LinkedHashMap<>();
        canonicalForm.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        canonicalForm.put("observedGeneration", input.observedGeneration());
        canonicalForm.put("configDigest", input.configDigest());
        canonicalForm.put("toolRegistryDigest", input.toolRegistryDigest());
        canonicalForm.put("members", sortedMembers);
        return Digests.sha256Hex(InternalCanonicalJsonV1.canonicalize(canonicalForm));
    }

    private EvidenceSnapshotBuilder() {
    }
}
