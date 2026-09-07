package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderArenaAdapter（M5-01）：订单域私有集策略面——PRIVATE 唯一主质量门、
 * source/name/adapter_version 固定、family 与原始 artifact 原样保留。
 */
class OrderArenaAdapterTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private static DatasetAdapter.ImportEntry entry(String caseKey, String family) {
        return new DatasetAdapter.ImportEntry(caseKey, family,
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey),
                "s3://arena-artifacts/" + caseKey + ".parquet");
    }

    private static DatasetAdapter.ImportRequest request(String version,
                                                        List<DatasetAdapter.ImportEntry> entries) {
        return new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef("fault-injection", version,
                        Digest.sha256Of("manifest-" + version)),
                PartitionClass.HOLDOUT, "https://arena.internal/dataset", "internal",
                "internal-use", entries);
    }

    @Test
    void privateSourceIdentityAndVersionHeader() {
        OrderArenaAdapter adapter = new OrderArenaAdapter();
        DatasetVersion v = adapter.datasetVersion(
                request("v1", List.of(entry("case-1", "family-f1"))), ID, NOW);

        assertThat(v.source()).isEqualTo("order-arena");
        assertThat(v.name()).isEqualTo("fault-injection");
        assertThat(v.sourceClass()).as("订单域私有集 = 唯一决定上线的主质量门")
                .isEqualTo(SourceClass.PRIVATE);
        assertThat(v.partitionClass()).isEqualTo(PartitionClass.HOLDOUT);
        assertThat(v.adapterVersion()).isEqualTo("oa-v1");
        assertThat(v.contentDigest()).isEqualTo(Digest.sha256Of("manifest-v1"));
        assertThat(v.scenarioFamilyDigest())
                .isEqualTo(DatasetVersion.familyDigest(adapter.importCases(
                        request("v1", List.of(entry("case-1", "family-f1"))))));
    }

    @Test
    void familyAndRawArtifactPreservedThroughConversion() {
        List<EvalCaseV1> cases = new OrderArenaAdapter().importCases(
                request("v1", List.of(entry("case-1", "family-f1"), entry("case-2", "family-f2"))));
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).scenarioFamilyId()).isEqualTo("family-f1");
        assertThat(cases.get(1).scenarioFamilyId()).isEqualTo("family-f2");
        assertThat(cases.get(0).rawArtifact()).containsEntry("raw", "case-1");
    }
}
