package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PrimaryDecision 严格解析 UT（决策协议面）：互斥分支/未声明字段拒绝之外，
 * 锚定 root_cause 结构化根因三元组的解析契约（根因评分贯通修复）——
 * 存在时三字段必须全非空（长度上限复用 TypedRootCause 128/64/128）；
 * kind=ROOT_CAUSE 缺 root_cause 不报错（向后兼容，下游诚实降级）。
 * symptom_codes（V151 症状评分贯通面）：缺席=null 不报错；存在即有界；
 * 来源标签值照传（本面不做词表过滤，规训归 prompt 协议面）。
 */
class PrimaryDecisionTest {

    private static Map<String, Object> claimRow() {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("claim_key", "c1");
        claim.put("kind", "ROOT_CAUSE");
        claim.put("statement", "payment 扣款按比例失败");
        claim.put("evidence_refs", List.of("ev-1"));
        return claim;
    }

    private static Map<String, Object> finalDecision(Map<String, Object> claim) {
        return Map.of("final", Map.of("claims", List.of(claim),
                "missing_information", List.of()));
    }

    private static Map<String, Object> triple(String component, String faultType,
            String reasonCode) {
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("component", component);
        rc.put("fault_type", faultType);
        rc.put("reason_code", reasonCode);
        return rc;
    }

    @Test
    @DisplayName("final 携带 root_cause 三元组 → 解析进 FinalClaim.rootCause")
    void rootCauseTripleParsed() {
        Map<String, Object> claim = claimRow();
        claim.put("root_cause",
                triple("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE"));

        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claim));

        assertThat(decision.branch()).isEqualTo(PrimaryDecision.Branch.FINAL);
        TypedRootCause rc = decision.finalAnswer().claims().get(0).rootCause();
        assertThat(rc).isNotNull();
        assertThat(rc.component()).isEqualTo("payment");
        assertThat(rc.faultType()).isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(rc.reasonCode()).isEqualTo("PAYMENT_CHARGE_FAILURE");
    }

    @Test
    @DisplayName("无 root_cause 键 → rootCause=null（旧协议形状正常解析）")
    void missingRootCauseParsesAsNull() {
        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claimRow()));

        assertThat(decision.finalAnswer().claims().get(0).rootCause()).isNull();
    }

    @Test
    @DisplayName("kind=ROOT_CAUSE 缺 root_cause 不报错（向后兼容，下游诚实降级）")
    void rootCauseKindWithoutTripleIsCompatible() {
        Map<String, Object> claim = claimRow();
        assertThat(claim.get("kind")).isEqualTo("ROOT_CAUSE");

        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claim));

        assertThat(decision.finalAnswer().claims().get(0).kind())
                .isEqualTo("ROOT_CAUSE");
        assertThat(decision.finalAnswer().claims().get(0).rootCause()).isNull();
    }

    @Test
    @DisplayName("root_cause 三字段缺一 → 解析拒绝（存在即须完整）")
    void tripleMissingOneFieldRejected() {
        Map<String, Object> claim = claimRow();
        Map<String, Object> rc = triple("payment", "BUSINESS_ERROR_RATE",
                "PAYMENT_CHARGE_FAILURE");
        rc.remove("reason_code");
        claim.put("root_cause", rc);

        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(claim)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason_code");
    }

    @Test
    @DisplayName("root_cause 未声明字段/非对象/超长 → 解析拒绝（禁静默裁字段）")
    void malformedTripleRejected() {
        Map<String, Object> extraKey = claimRow();
        Map<String, Object> rc = triple("payment", "BUSINESS_ERROR_RATE",
                "PAYMENT_CHARGE_FAILURE");
        rc.put("confidence", "high");
        extraKey.put("root_cause", rc);
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(extraKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明字段");

        Map<String, Object> notMap = claimRow();
        notMap.put("root_cause", "payment");
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(notMap)))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> overlong = claimRow();
        overlong.put("root_cause", triple("x".repeat(129), "BUSINESS_ERROR_RATE",
                "PAYMENT_CHARGE_FAILURE"));
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(overlong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
    }

    // ---------------------------------------------- 症状码（V151 评分贯通面）

    @Test
    @DisplayName("SYMPTOM claim 携带 symptom_codes → 解析进 FinalClaim.symptomCodes")
    void symptomCodesParsed() {
        Map<String, Object> claim = claimRow();
        claim.put("kind", "SYMPTOM");
        claim.put("symptom_codes", List.of("ArenaDuplicateOrders"));

        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claim));

        assertThat(decision.finalAnswer().claims().get(0).symptomCodes())
                .containsExactly("ArenaDuplicateOrders");
    }

    @Test
    @DisplayName("无 symptom_codes 键 → symptomCodes=null（缺席不报错，诚实降级）")
    void missingSymptomCodesParsesAsNull() {
        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claimRow()));

        assertThat(decision.finalAnswer().claims().get(0).symptomCodes()).isNull();
    }

    @Test
    @DisplayName("symptom_codes 来源标签值照传（sanitization 不做词表过滤，协议规训+诚实透传）")
    void symptomCodesPassThroughUnfiltered() {
        Map<String, Object> claim = claimRow();
        claim.put("kind", "SYMPTOM");
        claim.put("symptom_codes", List.of("logs", "prometheus"));

        PrimaryDecision decision = PrimaryDecision.parse(finalDecision(claim));

        assertThat(decision.finalAnswer().claims().get(0).symptomCodes())
                .containsExactly("logs", "prometheus");
    }

    @Test
    @DisplayName("symptom_codes 非数组/空条目/超界 → 解析拒绝")
    void malformedSymptomCodesRejected() {
        Map<String, Object> notList = claimRow();
        notList.put("symptom_codes", "SomeAlert");
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(notList)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptom_codes");

        Map<String, Object> blankEntry = claimRow();
        blankEntry.put("symptom_codes", List.of(" "));
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(blankEntry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptom_codes");

        Map<String, Object> overlongEntry = claimRow();
        overlongEntry.put("symptom_codes", List.of("x".repeat(257)));
        assertThatThrownBy(() -> PrimaryDecision.parse(finalDecision(overlongEntry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
    }
}
