package com.objwww.pr.control.alert.domain.evidence;

import java.time.Instant;
import java.util.Map;

/**
 * 证据 schema 校验（AM4 M4-19，五步纪律第 5 步——schema 校验与 digest 校验分开）：
 * 只认冻结的 schema_version；evidence_type/source 非空；时间窗不逆序；payload 必须是
 * 已解析映射。跨代（observed_generation）校验不在本件——那是正交的代际栅栏，由仓储
 * 准入面按 run 当前代判定。
 */
public final class EvidenceSchemaValidator {

    private EvidenceSchemaValidator() {
    }

    public static void validate(String schemaVersion, String evidenceType, String source,
            java.time.Instant timeStart, java.time.Instant timeEnd, Object payload,
            long observedGeneration) {
        if (!EvidenceEnvelope.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: schema_version 未知: "
                    + schemaVersion + "（期望 " + EvidenceEnvelope.SCHEMA_VERSION + "）");
        }
        if (evidenceType == null || evidenceType.isBlank()) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: evidence_type 不得为空");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: source 不得为空");
        }
        if (timeStart != null && timeEnd != null && timeStart.isAfter(timeEnd)) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: time 窗口逆序");
        }
        if (!(payload instanceof Map)) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: payload 必须是已解析映射");
        }
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("EVIDENCE_SCHEMA: generation 不得为负");
        }
    }
}
