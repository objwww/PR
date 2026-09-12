package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 人工补充材料（MC31/32，V96 incident_operator_material；MA-07 人为补充材料
 * 审计面）——操作者向事故/调查补充的观察、外链证据与判断：
 * <ul>
 *   <li>身份由认证端铸定（{@code operator}=AuthenticatedActor，非客户端自报）；</li>
 *   <li>{@code kind} 三分：OBSERVATION（观察）/EVIDENCE_LINK（必带 source_ref 的
 *       变更或外链证据）/JUDGMENT（无引用判断）——材料有来源且与实测证据区分；
 *       JUDGMENT 不进 validRefs，无证判断不能绕过 Claim 准入（X5 面不变）；</li>
 *   <li>MC32 CAS：{@code (incidentId, revision)} 唯一 = 材料集版本单调——同
 *       base_revision 并发提交至多一个生效，败方收 CONFLICT 可见；incident 已
 *       RESOLVED 后提交 = {@link Admission#REJECTED_LATE} 明确终态，不无限挂起。</li>
 * </ul>
 * 行只增不改（append-only 审计台账）。
 */
public record OperatorMaterial(
        UUID id,
        UUID incidentId,
        UUID runId,
        String operator,
        Kind kind,
        String sourceRef,
        String content,
        int baseRevision,
        int revision,
        Admission admission,
        Instant createdAt) {

    /** 材料种类（MC31：来源与可信度标注面） */
    public enum Kind {OBSERVATION, EVIDENCE_LINK, JUDGMENT}

    /** 准入（MC32 封闭集；CAS 败方不落行，应答可见） */
    public enum Admission {ACCEPTED, REJECTED_LATE}

    /** 内容上限（字符；V96 ck_mc31_material_content 同值背书） */
    public static final int MAX_CONTENT_CHARS = 20_000;

    public OperatorMaterial {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(operator, "operator");
        if (operator.isBlank()) {
            throw new IllegalArgumentException("operator 不得为空（认证端身份）");
        }
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.EVIDENCE_LINK && (sourceRef == null || sourceRef.isBlank())) {
            throw new IllegalArgumentException(
                    "EVIDENCE_LINK 必须带 source_ref（材料有来源面，MC31）");
        }
        Objects.requireNonNull(content, "content");
        if (content.isBlank() || content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "content 长度须在 1.." + MAX_CONTENT_CHARS);
        }
        if (baseRevision < 0 || revision < 1) {
            throw new IllegalArgumentException("revision 面: base≥0 且 revision≥1");
        }
        Objects.requireNonNull(admission, "admission");
        if (admission == Admission.ACCEPTED && revision != baseRevision + 1) {
            throw new IllegalArgumentException(
                    "ACCEPTED 行 revision 必须为 baseRevision+1（CAS 单调面，MC32）");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
