package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * CaseVersion（M5-01）record 校验：适用期 [valid_from, valid_to) 半开窗口合法性、
 * source_artifact_ref 引用保留、必填面非空。
 */
class CaseVersionTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");

    private static CaseVersion version(Instant from, Instant to) {
        return new CaseVersion(UUID.randomUUID(), UUID.randomUUID(), "case-1", "family-f1",
                from, to, Digest.sha256Of("case-1"),
                "s3://arena-artifacts/case-1.parquet",
                new EvalCaseV1("case-1", "family-f1",
                        new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                        List.of("PAYMENT_DUPLICATED"), Map.of("raw", "case-1")));
    }

    @Test
    void openEndedAndBoundedWindowsAccepted() {
        assertThat(version(T0, null).validTo()).as("valid_to null = 开放期").isNull();
        assertThat(version(T0, T1).validTo()).isEqualTo(T1);
    }

    @Test
    void validToNotAfterValidFromRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> version(T1, T0))
                .withMessageContaining("valid_to");
        assertThatIllegalArgumentException().isThrownBy(() -> version(T0, T0));
    }

    @Test
    void sourceArtifactRefPreservedAsPointer() {
        assertThat(version(T0, null).sourceArtifactRef())
                .isEqualTo("s3://arena-artifacts/case-1.parquet");
    }

    @Test
    void requiredFacesNonNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CaseVersion(UUID.randomUUID(), UUID.randomUUID(), " ", "family-f1",
                        T0, null, Digest.sha256Of("x"), null,
                        new EvalCaseV1("case-1", "family-f1",
                                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                                List.of(), Map.of())))
                .withMessageContaining("caseKey");
    }
}
