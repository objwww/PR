package com.objwww.pr.control.alert.domain.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvidenceEnvelope 穷举单测（AM4 M4-19，V16）：digest 五步纪律——入口 canonicalize 一次
 * → 存 canonical bytes → 对保存字节算 digest；schema 校验与 digest 校验分开；
 * 行摘要稳定（字段序无关）；同 schema_version 不同 generation 由调用方栅栏拒绝。
 */
class EvidenceEnvelopeTest {

    private static Map<String, Object> payload(String marker) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zeta", 1);
        payload.put("alpha", marker);
        return payload;
    }

    private static EvidenceEnvelope envelope(Map<String, Object> payload, long generation) {
        return EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(), null,
                "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, generation, "prom.query@1.0.0",
                Map.of("labels", "job=api"), Instant.ofEpochSecond(100),
                Instant.ofEpochSecond(200), payload);
    }

    @Test
    void utE01_入口canonicalize一次_字段序无关_同事实同digest() {
        EvidenceEnvelope a = envelope(payload("up"), 0);
        Map<String, Object> reordered = new TreeMap<>();
        reordered.put("alpha", "up");
        reordered.put("zeta", 1);
        EvidenceEnvelope b = envelope(reordered, 0);
        assertThat(a.payloadDigest()).isEqualTo(b.payloadDigest());
        assertThat(a.canonicalPayload()).isEqualTo(b.canonicalPayload());
        assertThat(a.canonicalPayload()).doesNotContain(" "); // 零空白 canonical 形
        assertThat(a.canonicalPayload())
                .contains("\"alpha\":\"up\"").contains("\"zeta\":1"); // 排序后 alpha 在前
    }

    @Test
    void utE02_内容变_digest必变_代际变_digest不变四正交() {
        EvidenceEnvelope base = envelope(payload("up"), 0);
        // payload_digest 只封内容；observed_generation 是正交维度（变代际不改内容 digest）
        EvidenceEnvelope otherGeneration = envelope(payload("up"), 1);
        assertThat(otherGeneration.payloadDigest()).isEqualTo(base.payloadDigest());
        assertThat(otherGeneration.observedGeneration()).isEqualTo(1);
        // 内容变 → digest 变
        assertThat(envelope(payload("down"), 0).payloadDigest())
                .isNotEqualTo(base.payloadDigest());
    }

    @Test
    void utE03_读出口对存储字节重算比对_篡改显式检出() {
        EvidenceEnvelope stored = envelope(payload("up"), 0);
        EvidenceEnvelope.verify(stored); // 原样回读通过
        EvidenceEnvelope tampered = new EvidenceEnvelope(stored.evidenceId(), stored.runId(),
                stored.taskId(), stored.evidenceType(), stored.schemaVersion(),
                stored.observedGeneration(), stored.source(), stored.scope(),
                stored.timeStart(), stored.timeEnd(),
                stored.canonicalPayload().replace("up", "down"), // 单字节篡改
                stored.payloadDigest());
        assertThatThrownBy(() -> EvidenceEnvelope.verify(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("篡改");
    }

    @Test
    void utE04_schema校验与digest校验分开_未知schemaVersion拒绝() {
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", "am3-legacy.v9", 0, "prom.query@1.0.0",
                Map.of(), null, null, payload("up")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
        // digest 面不参与 schema 判定：上面在校验阶段就拒绝，未走到 canonical/digest
    }

    @Test
    void utE05_非法定义拒绝_空type空source逆时序payload非映射() {
        Map<String, Object> payload = payload("x");
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, " ", EvidenceEnvelope.SCHEMA_VERSION, 0, "src", Map.of(),
                null, null, payload))
                .hasMessageContaining("evidence_type");
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, 0, " ",
                Map.of(), null, null, payload))
                .hasMessageContaining("source");
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, 0, "src", Map.of(),
                Instant.ofEpochSecond(200), Instant.ofEpochSecond(100), payload))
                .hasMessageContaining("time");
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, 0, "src", Map.of(),
                null, null, null))
                .hasMessageContaining("payload");
        assertThatThrownBy(() -> EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, -1, "src",
                Map.of(), null, null, payload))
                .hasMessageContaining("generation");
    }

    @Test
    void utE06_行摘要稳定_任一包封字段变行摘要变() {
        EvidenceEnvelope base = envelope(payload("up"), 0);
        Map<String, Object> scope2 = Map.of("labels", "job=api2");
        EvidenceEnvelope otherScope = new EvidenceEnvelope(base.evidenceId(), base.runId(),
                base.taskId(), base.evidenceType(), base.schemaVersion(),
                base.observedGeneration(), base.source(), scope2,
                base.timeStart(), base.timeEnd(), base.canonicalPayload(), base.payloadDigest());
        assertThat(otherScope.rowDigest()).isNotEqualTo(base.rowDigest());
        assertThat(base.rowDigest()).isEqualTo(base.rowDigest()); // 稳定
    }
}
