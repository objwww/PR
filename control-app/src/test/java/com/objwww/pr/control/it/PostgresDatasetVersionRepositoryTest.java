package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;
import com.objwww.pr.control.infrastructure.persistence.PostgresDatasetVersionRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresDatasetVersionRepository 真 PG 组件测试（M5-01）：
 * 历史不可覆盖（同 (name,version) 禁重生 / 同 (dataset,case_key) 禁覆盖）、
 * 适用期解析（valid_from <= at < valid_to 或开放期）、eval_app 授权面
 * （insert/select 可用，UPDATE/DELETE 被授权拒绝——INV-AM5-1 的 DB 面证据）。
 * 本机无 Docker 自动跳过（真证据待 195 释放后统一补）。
 */
class PostgresDatasetVersionRepositoryTest extends PostgresITBase {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");

    private PostgresDatasetVersionRepository repo;

    @BeforeEach
    void setUp() {
        adminJdbc.sql("DELETE FROM case_version").update();
        adminJdbc.sql("DELETE FROM dataset_version").update();
        repo = new PostgresDatasetVersionRepository(evalJdbc);
    }

    private static DatasetVersion dataset(String version, Digest familyDigest) {
        return new DatasetVersion(UUID.randomUUID(), "order-arena", "fault-injection", version,
                "https://arena.internal/dataset", "internal", "internal-use",
                Digest.sha256Of("manifest-" + version), "oa-v1", Instant.now(),
                SourceClass.PRIVATE, PartitionClass.TUNING, familyDigest);
    }

    private static EvalCaseV1 content(String caseKey) {
        return new EvalCaseV1(caseKey, "family-f1",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey));
    }

    private static CaseVersion caseRow(UUID datasetId, String caseKey, Instant from, Instant to) {
        return new CaseVersion(UUID.randomUUID(), datasetId, caseKey, "family-f1",
                from, to, Digest.sha256Of(caseKey + "-" + to),
                "s3://arena-artifacts/" + caseKey + ".parquet", content(caseKey));
    }

    @Test
    void datasetVersionReInsertSameIdentityRejected() {
        DatasetVersion v = dataset("v1", Digest.sha256Of("families"));
        repo.insertDatasetVersion(v);
        // 同 (name,version) 不同 id 重生 → 拒绝（落码方案 §M5-01② 唯一约束）
        assertThatThrownBy(() -> repo.insertDatasetVersion(
                dataset("v1", Digest.sha256Of("families"))))
                .isInstanceOf(DuplicateKeyException.class)
                .as("同 (name,version) 数据集版本禁重生——历史不可覆盖");
    }

    @Test
    void caseVersionReInsertSameCaseRejected() {
        DatasetVersion v = dataset("v1", Digest.sha256Of("families"));
        repo.insertDatasetVersion(v);
        CaseVersion c = caseRow(v.id(), "case-1", T0, null);
        assertThat(repo.insertCaseVersion(c)).isTrue();
        assertThat(repo.insertCaseVersion(caseRow(v.id(), "case-1", T0, null)))
                .as("同 (dataset,case_key) 案例版本禁覆盖")
                .isFalse();
    }

    @Test
    void validityWindowResolvesPointInTimeAcrossDatasetVersions() {
        DatasetVersion v1 = dataset("v1", Digest.sha256Of("families"));
        repo.insertDatasetVersion(v1);
        // 纠错语义：新 dataset_version 携带修正行，旧版本行原样保留
        DatasetVersion v2 = dataset("v2", Digest.sha256Of("families"));
        repo.insertDatasetVersion(v2);

        repo.insertCaseVersion(caseRow(v1.id(), "case-1", T0, T1));
        repo.insertCaseVersion(caseRow(v1.id(), "case-2", T1, null));
        repo.insertCaseVersion(caseRow(v2.id(), "case-1", T1, null));

        assertThat(repo.findCasesValidAt(v1.id(), T0.plusSeconds(60)))
                .as("T0 窗口内 v1 的 case-1 生效")
                .extracting(CaseVersion::caseKey)
                .containsExactly("case-1");
        assertThat(repo.findCasesValidAt(v1.id(), T1))
                .as("valid_to 半开区间：at=T1 时 case-1 已失效、case-2 开放期生效")
                .extracting(CaseVersion::caseKey)
                .containsExactly("case-2");
        assertThat(repo.findCasesValidAt(v2.id(), T1.plusSeconds(60)))
                .as("v2 修正行独立生效，v1 历史不被波及")
                .extracting(CaseVersion::caseKey)
                .containsExactly("case-1");
    }

    @Test
    void evalAppCanInsertButCannotUpdateOrDelete() {
        DatasetVersion v = dataset("v1", Digest.sha256Of("families"));
        repo.insertDatasetVersion(v);

        // UPDATE/DELETE 授权面为 0（V20 只授 select,insert）——Spring 把 PG 42501
        // 转译为 DataAccessException 族，精确子类随驱动/方言变化，断言根类型
        assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql(
                        "UPDATE dataset_version SET name = 'renamed' WHERE id = :id")
                        .param("id", v.id()).update());
        assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql(
                        "DELETE FROM dataset_version WHERE id = :id")
                        .param("id", v.id()).update());

        // 行仍在（未被删掉）——真库授权兜底，不是仓储层的绅士协定
        assertThat(repo.findDataset("fault-injection", "v1")).isPresent();
    }

    @Test
    void adapterOutputRoundTripsThroughPayloadJsonb() {
        DatasetAdapter adapter = new com.objwww.pr.control.eval.infrastructure.adapter.OrderArenaAdapter();
        List<EvalCaseV1> cases = adapter.importCases(new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef("fault-injection", "v1",
                        Digest.sha256Of("manifest")),
                PartitionClass.TUNING, "https://arena.internal/dataset", "internal",
                "internal-use", List.of(new DatasetAdapter.ImportEntry("case-1", "family-f1",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", "case-1"),
                "s3://arena-artifacts/case-1.parquet"))));
        DatasetVersion v = adapter.datasetVersion(new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef("fault-injection", "v1", Digest.sha256Of("manifest")),
                PartitionClass.TUNING, "https://arena.internal/dataset", "internal",
                "internal-use", List.of()), UUID.randomUUID(), Instant.now());
        repo.insertDatasetVersion(v);
        CaseVersion c = new CaseVersion(UUID.randomUUID(), v.id(), "case-1", "family-f1",
                T0, null, Digest.sha256Of("case-1"),
                "s3://arena-artifacts/case-1.parquet", cases.get(0));
        repo.insertCaseVersion(c);

        CaseVersion loaded = repo.findCasesValidAt(v.id(), T0.plusSeconds(1)).get(0);
        assertThat(loaded.caseContent()).isEqualTo(cases.get(0));
        assertThat(loaded.sourceArtifactRef()).isEqualTo("s3://arena-artifacts/case-1.parquet");
        assertThat(repo.findDataset("fault-injection", "v1"))
                .as("dataset 行回读：分级/分区/族摘要全量还原")
                .hasValueSatisfying(d -> {
                    assertThat(d.sourceClass()).isEqualTo(SourceClass.PRIVATE);
                    assertThat(d.partitionClass()).isEqualTo(PartitionClass.TUNING);
                    assertThat(d.scenarioFamilyDigest()).isEqualTo(v.scenarioFamilyDigest());
                });
    }
}
