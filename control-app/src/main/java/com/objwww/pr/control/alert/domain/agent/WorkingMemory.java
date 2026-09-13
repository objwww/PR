package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作记忆快照（R10/MA-02，R7 方案 §19.2 三层存储的"工作记忆快照"层）：
 * facts = 带 supportRefs 的判断（与原始 Observation 保持类别区分）；已推翻假设
 * 标 REJECTED 保留理由不删除（MC05：早期反证/已排除方向在后续多步保留）。
 *
 * <p><b>深冻结（MC06）</b>：紧凑构造器对槽做逐值不可变拷贝——外部修改调用方
 * 手里的 map/list 不影响已构造快照与 digest；digest = 规范化槽 JSON 的 sha256，
 * 构造期一次算定，此后不漂移。
 *
 * <p>checkpoint_revision = 该快照所属检查点代数：同修订重放幂等（append 返回
 * 既有行），检查点经 memory_id/digest 钉住本步所用快照（MC07 恢复语义：重驱读
 * 同快照，不另生成）。
 *
 * <p><b>CL-06 协议分型（§5.1）</b>：schema_version=1 旧协议（revision 存
 * decision_seq 语义，存量行不回填）；schema_version=2 新协议（checkpoint_revision
 * 严格对应 rca_primary_checkpoint.revision）。parent_memory_id = 上一版快照的
 * 不可变引用（§5.2 累计链：宿主从 checkpoint.memory_id 精确读上一版，按内容
 * 去重合并本版 delta——反证跨轮保留，不以 latestByTask 替代指针）。
 */
public record WorkingMemory(UUID id, UUID runId, UUID taskId, long checkpointRevision,
                            Map<String, List<String>> slots, String memoryDigest,
                            Long configEpoch, int schemaVersion, UUID parentMemoryId,
                            Instant createdAt) {

    /** 槽契约四槽（§19.2 词表）；缺失槽以空表如实占位 */
    public static final List<String> SLOT_KEYS =
            List.of("hypotheses", "ruled_out", "counter_evidence_refs", "open_gaps");

    /** CL-06 协议版本：1=旧（revision 存 decisionSeq），2=新（真 checkpoint revision） */
    public static final int SCHEMA_V1 = 1;
    public static final int SCHEMA_V2 = 2;

    public WorkingMemory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(memoryDigest, "memoryDigest");
        Objects.requireNonNull(createdAt, "createdAt");
        if (checkpointRevision < 0) {
            throw new IllegalArgumentException("checkpoint_revision 不得为负");
        }
        if (schemaVersion < SCHEMA_V1 || schemaVersion > SCHEMA_V2) {
            throw new IllegalArgumentException("schema_version 必须为 1 或 2: " + schemaVersion);
        }
        slots = deepFreeze(slots);
    }

    /** 旧协议兼容构造（schema1：checkpoint_revision=decision_seq 语义） */
    public WorkingMemory(UUID id, UUID runId, UUID taskId, long checkpointRevision,
            Map<String, List<String>> slots, String memoryDigest, Long configEpoch,
            Instant createdAt) {
        this(id, runId, taskId, checkpointRevision, slots, memoryDigest, configEpoch,
                SCHEMA_V1, null, createdAt);
    }

    /** 构造工厂（digest 在此一次算定：规范化槽 JSON 的 sha256） */
    public static WorkingMemory of(UUID id, UUID runId, UUID taskId,
            long checkpointRevision, Map<String, List<String>> slots, Long configEpoch,
            Instant createdAt) {
        String digest = Digest.sha256Of(canonicalJson(slots)).value();
        return new WorkingMemory(id, runId, taskId, checkpointRevision, slots,
                digest, configEpoch, createdAt);
    }

    /** CL-06 新协议工厂：真 revision + 不可变父引用（累计链） */
    public static WorkingMemory ofV2(UUID id, UUID runId, UUID taskId,
            long checkpointRevision, Map<String, List<String>> slots, Long configEpoch,
            UUID parentMemoryId, Instant createdAt) {
        String digest = Digest.sha256Of(canonicalJson(slots)).value();
        return new WorkingMemory(id, runId, taskId, checkpointRevision, slots,
                digest, configEpoch, SCHEMA_V2, parentMemoryId, createdAt);
    }

    /**
     * 槽规范化 JSON（键序按 SLOT_KEYS 契约序，值保序）——深冻结比对锚。
     * 域纯度（架构门：domain.agent 零 Jackson/Spring 依赖）故手写序列化：字符串槽
     * 仅做 引号/反斜杠/控制字符 转义（控制字符短形 b/f/n/r/t、余用反斜杠 u + 四位
     * 小写十六进制），非 ASCII 保持原样——与 Jackson 默认序逐字节一致，digest 可对拍。
     */
    public static String canonicalJson(Map<String, List<String>> slots) {
        Map<String, List<String>> canonical = new LinkedHashMap<>();
        for (String key : SLOT_KEYS) {
            canonical.put(key, List.copyOf(slots.getOrDefault(key, List.of())));
        }
        StringBuilder sb = new StringBuilder(128).append('{');
        int keyCount = 0;
        for (Map.Entry<String, List<String>> entry : canonical.entrySet()) {
            if (keyCount++ > 0) {
                sb.append(',');
            }
            quote(sb, entry.getKey()).append(':').append('[');
            List<String> values = entry.getValue();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                quote(sb, values.get(i));
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** JSON 字符串字面量（紧凑构造器已深冻结，值非 null） */
    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }

    /** 逐值不可变拷贝（外部修改候选不影响已构造快照——MC06 深冻结） */
    private static Map<String, List<String>> deepFreeze(Map<String, List<String>> slots) {
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : slots.entrySet()) {
            frozen.put(entry.getKey(), entry.getValue() == null
                    ? List.of() : List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }
}
