package com.objwww.pr.control.eval.domain.port;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DatasetAdapter 端口契约（M5-01）：ImportRequest/DatasetRef 校验、
 * manifest digest 锚定传递、EvalCaseV1 规范化转换、family 整组分区断言
 * （同 scenario_family_id 拆分跨 TUNING/HOLDOUT 直接拒绝——M5-02 V21 DB 唯一
 * 约束之前的应用层门）。
 */
class DatasetAdapterTest {

    private static DatasetAdapter.ImportEntry entry(String caseKey, String family) {
        return new DatasetAdapter.ImportEntry(caseKey, family,
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey),
                "s3://artifacts/" + caseKey + ".parquet");
    }

    private static DatasetAdapter.ImportRequest request(String name, String version,
                                                        List<DatasetAdapter.ImportEntry> entries) {
        return new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef(name, version, Digest.sha256Of("manifest-" + version)),
                PartitionClass.TUNING, "https://example.test/dataset", "benchmark", "public", entries);
    }

    @Test
    void importRequestValidatesRefAndFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> request(" ", "v1", List.of()))
                .withMessageContaining("name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetAdapter.ImportRequest(
                        new DatasetAdapter.DatasetRef("fault-injection", "v1", Digest.sha256Of("m")),
                        PartitionClass.HOLDOUT, " ", "license", "public", List.of()))
                .withMessageContaining("sourceUri");
        assertThatThrownBy(() -> new DatasetAdapter.DatasetRef("fault-injection", "v1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("manifestDigest");
    }

    @Test
    void manifestDigestAnchorsIntoDatasetVersionHeader() {
        DatasetVersion v = new OrderArenaFixtureAdapter().datasetVersion(
                request("fault-injection", "v1", List.of(entry("case-1", "family-f1"))),
                java.util.UUID.randomUUID(), java.time.Instant.now());
        assertThat(v.contentDigest()).as("content_digest = datasetRef.manifestDigest（版本锚）")
                .isEqualTo(Digest.sha256Of("manifest-v1"));
        assertThat(v.name()).isEqualTo("fault-injection");
        assertThat(v.version()).isEqualTo("v1");
    }

    @Test
    void canonicalConversionPreservesFamilyAndRawArtifact() {
        List<EvalCaseV1> cases = new OrderArenaFixtureAdapter().importCases(
                request("fault-injection", "v1",
                        List.of(entry("case-1", "family-f1"), entry("case-2", "family-f2"))));
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).scenarioFamilyId()).isEqualTo("family-f1");
        assertThat(cases.get(0).rawArtifact()).containsEntry("raw", "case-1");
        assertThat(cases.get(1).caseKey()).isEqualTo("case-2");
    }

    @Test
    void sameFamilySplitAcrossPartitionsRejected() {
        Map<String, PartitionClass> assigned = new HashMap<>(Map.of("family-f1", PartitionClass.TUNING));

        // 同族已划 TUNING，再划 HOLDOUT → 直接拒绝（整组分区违约）
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatasetAdapter.assertFamilyPartition(
                        "family-f1", PartitionClass.HOLDOUT, assigned))
                .withMessageContaining("family-f1")
                .withMessageContaining("TUNING")
                .withMessageContaining("HOLDOUT");
        // 同族同分区重申 → 允许（幂等）
        DatasetAdapter.assertFamilyPartition("family-f1", PartitionClass.TUNING, assigned);
        // 新族首划 → 允许
        DatasetAdapter.assertFamilyPartition("family-f2", PartitionClass.HOLDOUT, assigned);
    }

    @Test
    void blankFamilyEntryRejectedWholeImport() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderArenaFixtureAdapter().importCases(
                        request("fault-injection", "v1",
                                List.of(entry("case-1", "family-f1"), entry("case-2", " ")))));
    }

    /** 端口契约测试用的最小实现（三真适配器的策略面由各自 *AdapterTest 覆盖） */
    private static final class OrderArenaFixtureAdapter implements DatasetAdapter {
        @Override
        public DatasetVersion datasetVersion(ImportRequest request,
                                             java.util.UUID id, java.time.Instant importedAt) {
            return DatasetAdapter.version(request, id, importedAt, "order-arena",
                    com.objwww.pr.control.eval.domain.model.SourceClass.PRIVATE,
                    request.partitionClass(), "oa-v1",
                    DatasetVersion.familyDigest(DatasetAdapter.cases(request)));
        }

        @Override
        public List<EvalCaseV1> importCases(ImportRequest request) {
            return DatasetAdapter.cases(request);
        }
    }
}
