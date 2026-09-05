package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;

import java.util.List;
import java.util.Objects;

/**
 * EvidencePackage v2 类型化断言（AM3 §6.3；M3-01）：
 * {claim_type, status(TRUE|FALSE|UNKNOWN), component, fault_type, symptom_codes[], evidence_refs[]}。
 *
 * <p>status 复用 AM4 claim 域的 ClaimStatus（三态语义同源，不重复造枚举）。
 * symptom_codes/evidence_refs 允许空数组（因果型断言可以不挂症状码），
 * 但条目本身必须非空且有界。全部字段必填——线缆契约不留歧义形态
 * （strict response_format 也按全键要求下发，BA-14 兼容端点漏键照样结构拒绝）。
 *
 * <p>评分落点（§6.4）：symptom_coverage 取 symptom_codes；silence_penalty 的
 * "claims 非空"指本类型列表非空；component/fault_type 进同义词表归一。
 */
public record ReportClaim(String claimType,
                          ClaimStatus status,
                          String component,
                          String faultType,
                          List<String> symptomCodes,
                          List<String> evidenceRefs) {

    public static final int MAX_CLAIM_TYPE_CHARS = 64;
    public static final int MAX_SYMPTOM_CODES = 32;
    public static final int MAX_EVIDENCE_REFS = 16;
    public static final int MAX_CODE_CHARS = 256;

    public ReportClaim {
        claimType = TypedRootCause.requireBounded(claimType, "claim_type", MAX_CLAIM_TYPE_CHARS);
        Objects.requireNonNull(status, "status 不得为 null");
        component = TypedRootCause.requireBounded(component, "component", TypedRootCause.MAX_COMPONENT_CHARS);
        faultType = TypedRootCause.requireBounded(faultType, "fault_type", TypedRootCause.MAX_FAULT_TYPE_CHARS);
        symptomCodes = requireBoundedList(symptomCodes, "symptom_codes", MAX_SYMPTOM_CODES);
        evidenceRefs = requireBoundedList(evidenceRefs, "evidence_refs", MAX_EVIDENCE_REFS);
    }

    private static List<String> requireBoundedList(List<String> values, String field, int maxSize) {
        Objects.requireNonNull(values, field + " 不得为 null");
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(field + " 条数超上限: " + values.size() + " > " + maxSize);
        }
        for (String value : values) {
            TypedRootCause.requireBounded(value, field + " 条目", MAX_CODE_CHARS);
        }
        return List.copyOf(values);
    }
}
