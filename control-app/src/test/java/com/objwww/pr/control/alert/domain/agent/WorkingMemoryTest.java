package com.objwww.pr.control.alert.domain.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R10/MA-02 工作记忆快照单测（MC06 深冻结 + 槽契约）：候选外部修改不渗入已构造
 * 快照、digest 构造期一次算定不漂移、规范化 JSON 键序按 SLOT_KEYS 契约序（与输入
 * 序无关——跨 Map 实现 digest 稳定的前提）、缺失槽空表占位、修订非负。
 * 落档行为（uq 幂等/授权面）由 PostgresWorkingMemoryIT 覆盖（195 补真证据）。
 */
class WorkingMemoryTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    @Test
    void mc06深冻结_候选外部修改不渗入快照_digest不漂移() {
        Map<String, List<String>> candidate = new HashMap<>();
        candidate.put("hypotheses", new ArrayList<>(List.of("支付网关超时")));
        WorkingMemory memory = WorkingMemory.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, candidate, 7L, NOW);
        String digestBefore = memory.memoryDigest();

        candidate.get("hypotheses").add("事后塞入的第二假设");
        candidate.put("open_gaps", List.of("事后补的槽"));

        assertThat(memory.slots().get("hypotheses"))
                .as("候选列表后续改动不渗入已构造快照（MC06）")
                .containsExactly("支付网关超时");
        assertThat(memory.slots()).as("事后新槽不出现").doesNotContainKey("open_gaps");
        assertThat(memory.memoryDigest()).as("digest 构造期一次算定").isEqualTo(digestBefore);
        assertThatThrownBy(() -> memory.slots().put("x", List.of("y")))
                .as("快照自身不可变").isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 规范化JSON键序按槽契约_与输入序无关_同槽同值同digest() {
        Map<String, List<String>> reversed = new LinkedHashMap<>();
        reversed.put("open_gaps", List.of("g-1"));
        reversed.put("counter_evidence_refs", List.of());
        reversed.put("ruled_out", List.of("r-1: 已排除"));
        reversed.put("hypotheses", List.of("h-1"));

        Map<String, List<String>> forward = new LinkedHashMap<>();
        for (String key : WorkingMemory.SLOT_KEYS) {
            forward.put(key, reversed.get(key));
        }

        assertThat(WorkingMemory.canonicalJson(reversed))
                .as("键序按 SLOT_KEYS 契约序，与输入序无关")
                .isEqualTo(WorkingMemory.canonicalJson(forward))
                .isEqualTo("{\"hypotheses\":[\"h-1\"],\"ruled_out\":[\"r-1: 已排除\"],"
                        + "\"counter_evidence_refs\":[],\"open_gaps\":[\"g-1\"]}");

        WorkingMemory a = WorkingMemory.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, reversed, null, NOW);
        WorkingMemory b = WorkingMemory.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, forward, null, NOW);
        assertThat(a.memoryDigest()).as("digest 身份在内容不在行")
                .isEqualTo(b.memoryDigest());
    }

    @Test
    void 规范化JSON转义契约_引号反斜杠控制字符_中文原样() {
        Map<String, List<String>> slots = Map.of(
                "hypotheses", List.of("带\"引号\"与\\反斜杠", "换行\n与制表\t"),
                "ruled_out", List.of("中文与emoji🚨原样"));
        assertThat(WorkingMemory.canonicalJson(slots))
                .isEqualTo("{\"hypotheses\":[\"带\\\"引号\\\"与\\\\反斜杠\",\"换行\\n与制表\\t\"],"
                        + "\"ruled_out\":[\"中文与emoji🚨原样\"],"
                        + "\"counter_evidence_refs\":[],\"open_gaps\":[]}");
    }

    @Test
    void 缺失槽以空表占位_修订负数failFast() {
        String canonical = WorkingMemory.canonicalJson(
                Map.of("hypotheses", List.of("h-1")));
        assertThat(canonical).as("槽契约四槽齐全（缺失如实空表占位，不缺键）")
                .isEqualTo("{\"hypotheses\":[\"h-1\"],\"ruled_out\":[],"
                        + "\"counter_evidence_refs\":[],\"open_gaps\":[]}");

        assertThatThrownBy(() -> new WorkingMemory(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), -1, Map.of(), "d", null, NOW))
                .as("checkpoint_revision 非负")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
