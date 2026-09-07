package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalCaseV1（M5-01）：统一案例契约的必填校验与防御性拷贝
 * （期望面与 AM3 评分器同构；rawArtifact 原始证据保留）。
 */
class EvalCaseV1Test {

    private static EvalCaseV1 caseV1(String caseKey) {
        return new EvalCaseV1(caseKey, "family-f1",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey));
    }

    @Test
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> new EvalCaseV1(" ", "family-f1",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseKey");
        assertThatThrownBy(() -> new EvalCaseV1("case-1", "family-f1", null, List.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void collectionsDefensivelyCopied() {
        var mutableCodes = new java.util.ArrayList<>(List.of("PAYMENT_DUPLICATED"));
        var mutableRaw = new java.util.HashMap<String, Object>(Map.of("raw", "x"));
        EvalCaseV1 c = new EvalCaseV1("case-1", "family-f1",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                mutableCodes, mutableRaw);

        mutableCodes.add("INJECTED");
        mutableRaw.put("injected", true);
        assertThat(c.expectedSymptomCodes()).containsExactly("PAYMENT_DUPLICATED");
        assertThat(c.rawArtifact()).containsOnlyKeys("raw");
    }

    @Test
    void rawArtifactPreservedVerbatim() {
        assertThat(caseV1("case-9").rawArtifact()).containsEntry("raw", "case-9");
    }
}
