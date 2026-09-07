package com.objwww.pr.control.alert.domain.evidence;

import com.objwww.pr.shared.Digests;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 证据信封（AM4 M4-19，V16；项目原生——借鉴 in-toto envelope 思想不照搬供应链 schema）。
 *
 * <p><b>四正交维度分别校验</b>：schema_version（结构版本，SchemaValidator 面）/
 * observed_generation（事故代际，仓储准入面按 run 当前代栅栏）/ snapshot_digest
 * （冻结输入集，归属快照见 M4-20）/ payload_digest（内容完整性，本件五步纪律）。
 * "跨代拒绝 = version URI 不匹配"是错误口径——schema 版本与代际互不推导。
 *
 * <p><b>digest 五步纪律</b>：①入口 canonicalize 一次（create 内）→ ②存 canonical bytes
 * （{@link #canonicalPayload()} 原样落库，TEXT 列禁 jsonb——jsonb 会重排/重格式化，
 * 字节无法原样回读）→ ③对保存字节算 digest（create 内对 canonical 串算
 * payload_digest）→ ④读出对原样字节重算比对（{@link #verify}）→ ⑤schema 校验与
 * digest 校验分开（EvidenceSchemaValidator ≠ verify）。
 *
 * <p>行摘要 {@link #rowDigest()} = 封包业务字段的 canonical sha256（不含 id 列——
 * 行身份篡改经 FK/查找失败暴露；内容篡改经 payload_digest、封包篡改经 rowDigest 检出）。
 */
public record EvidenceEnvelope(
        UUID evidenceId,
        UUID runId,
        UUID taskId,
        String evidenceType,
        String schemaVersion,
        long observedGeneration,
        String source,
        Map<String, Object> scope,
        Instant timeStart,
        Instant timeEnd,
        String canonicalPayload,
        String payloadDigest) {

    public static final String SCHEMA_VERSION = "am4-evidence.v1";

    /** 入口（五步 ①③⑤）：schema 校验 → canonicalize 一次 → 对 canonical 字节算 digest */
    public static EvidenceEnvelope create(UUID evidenceId, UUID runId, UUID taskId,
            String evidenceType, String schemaVersion, long observedGeneration, String source,
            Map<String, Object> scope, Instant timeStart, Instant timeEnd,
            Map<String, Object> payload) {
        EvidenceSchemaValidator.validate(schemaVersion, evidenceType, source,
                timeStart, timeEnd, payload, observedGeneration);
        String canonical = com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                .canonicalize(payload);
        return new EvidenceEnvelope(evidenceId, runId, taskId, evidenceType, schemaVersion,
                observedGeneration, source,
                scope == null ? Map.of() : scope, timeStart, timeEnd, canonical,
                Digests.sha256Hex(canonical));
    }

    /** 读出口（五步 ④）：对存储字节重算比对；不一致 = 篡改，显式拒绝 */
    public static EvidenceEnvelope verify(EvidenceEnvelope stored) {
        String actual = Digests.sha256Hex(stored.canonicalPayload);
        if (!actual.equals(stored.payloadDigest)) {
            throw new IllegalStateException("EVIDENCE_TAMPERED: payload 字节与 digest 不符"
                    + "（单字节篡改可检出）: evidence=" + stored.evidenceId());
        }
        return stored;
    }

    /** 行摘要：封包业务字段 canonical sha256（scope 同样 canonical 化后纳入） */
    public String rowDigest() {
        Map<String, Object> canonicalForm = new LinkedHashMap<>();
        canonicalForm.put("schemaVersion", schemaVersion);
        canonicalForm.put("evidenceType", evidenceType);
        canonicalForm.put("observedGeneration", observedGeneration);
        canonicalForm.put("source", source);
        canonicalForm.put("scope", scope);
        canonicalForm.put("timeStart", timeStart == null ? null : timeStart.toEpochMilli());
        canonicalForm.put("timeEnd", timeEnd == null ? null : timeEnd.toEpochMilli());
        canonicalForm.put("payloadDigest", payloadDigest);
        return com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                .sha256(canonicalForm);
    }
}
