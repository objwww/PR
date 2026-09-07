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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * DatasetVersion（M5-01）record 校验：来源九字段 + 分级/分区身份非空且非 blank、
 * 族清单摘要（familyDigest）稳定性与差异敏感。
 */
class DatasetVersionTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private static EvalCaseV1 caseOf(String caseKey, String family) {
        return new EvalCaseV1(caseKey, family,
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey));
    }

    private static DatasetVersion full(Digest familyDigest) {
        return new DatasetVersion(UUID.randomUUID(), "order-arena", "fault-injection", "v1",
                "https://arena.internal/dataset", "internal", "internal-use",
                Digest.sha256Of("manifest-v1"), "oa-v1", NOW,
                SourceClass.PRIVATE, PartitionClass.TUNING, familyDigest);
    }

    @Test
    void nineSourceFieldsPlusPartitionIdentityRoundTrip() {
        DatasetVersion v = full(Digest.sha256Of("families"));
        assertThat(v.source()).isEqualTo("order-arena");
        assertThat(v.name()).isEqualTo("fault-injection");
        assertThat(v.version()).isEqualTo("v1");
        assertThat(v.sourceUri()).isEqualTo("https://arena.internal/dataset");
        assertThat(v.license()).isEqualTo("internal");
        assertThat(v.accessClass()).isEqualTo("internal-use");
        assertThat(v.contentDigest()).isEqualTo(com.objwww.pr.shared.Digest.sha256Of("manifest-v1"));
        assertThat(v.adapterVersion()).isEqualTo("oa-v1");
        assertThat(v.importedAt()).isEqualTo(NOW);
        assertThat(v.sourceClass()).isEqualTo(SourceClass.PRIVATE);
        assertThat(v.partitionClass()).isEqualTo(PartitionClass.TUNING);
    }

    @Test
    void blankOrNullFieldsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetVersion(UUID.randomUUID(), " ", "fault-injection", "v1",
                        "uri", "license", "public", Digest.sha256Of("m"),
                        "oa-v1", NOW, SourceClass.PRIVATE, PartitionClass.TUNING,
                        Digest.sha256Of("f")))
                .withMessageContaining("source");
        assertThatNullPointerException()
                .isThrownBy(() -> new DatasetVersion(UUID.randomUUID(), "order-arena", "fault-injection",
                        "v1", "uri", "license", "public", Digest.sha256Of("m"),
                        "oa-v1", NOW, SourceClass.PRIVATE, null,
                        Digest.sha256Of("f")));
    }

    @Test
    void familyDigestIsOrderInsensitiveButChangeSensitive() {
        Digest d1 = DatasetVersion.familyDigest(List.of(caseOf("c1", "family-a"), caseOf("c2", "family-b")));
        Digest d2 = DatasetVersion.familyDigest(List.of(caseOf("c9", "family-b"), caseOf("c8", "family-a")));
        Digest d3 = DatasetVersion.familyDigest(List.of(caseOf("c1", "family-a"), caseOf("c2", "family-c")));

        assertThat(d1).as("同族集合不同案例顺序 → 同摘要").isEqualTo(d2);
        assertThat(d1).as("任一族变更 → 摘要变化（版本锚敏感）").isNotEqualTo(d3);
        assertThat(d1).isNotEqualTo(DatasetVersion.familyDigest(List.of(caseOf("c1", "family-a"))));
    }
}
