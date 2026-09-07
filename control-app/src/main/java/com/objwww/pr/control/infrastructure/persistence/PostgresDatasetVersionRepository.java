package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.repository.DatasetVersionRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * dataset_version / case_version 的 Postgres 实现（V20；M5-01）。
 *
 * <p>insert-only：类内不存在任何 UPDATE/DELETE 语句；授权面（eval_app 只
 * select,insert）由 V20 兜底。timestamptz 绑定显式 {@code Timestamp.from}
 * （BA-31：pgjdbc 不识别 Instant 直绑）；jsonb 走 CAST 字符串（V10 同构）。
 */
public class PostgresDatasetVersionRepository implements DatasetVersionRepository {

    private static final String INSERT_DATASET_SQL = """
            INSERT INTO dataset_version (
                id, source, name, version, source_uri, license, access_class,
                content_digest, adapter_version, imported_at, source_class,
                partition_class, scenario_family_digest
            ) VALUES (
                :id, :source, :name, :version, :sourceUri, :license, :accessClass,
                :contentDigest, :adapterVersion, :importedAt, :sourceClass,
                :partitionClass, :scenarioFamilyDigest
            )
            """;

    private static final String INSERT_CASE_SQL = """
            INSERT INTO case_version (
                id, dataset_version_id, case_key, scenario_family_id,
                valid_from, valid_to, content_digest, payload, source_artifact_ref
            ) VALUES (
                :id, :datasetVersionId, :caseKey, :scenarioFamilyId,
                :validFrom, :validTo, :contentDigest, CAST(:payload AS jsonb),
                :sourceArtifactRef
            )
            """;

    private static final String FIND_DATASET_SQL = """
            SELECT id, source, name, version, source_uri, license, access_class,
                   content_digest, adapter_version, imported_at, source_class,
                   partition_class, scenario_family_digest
            FROM dataset_version
            WHERE name = :name AND version = :version
            """;

    private static final String FIND_CASES_VALID_AT_SQL = """
            SELECT id, dataset_version_id, case_key, scenario_family_id,
                   valid_from, valid_to, content_digest, payload, source_artifact_ref
            FROM case_version
            WHERE dataset_version_id = :datasetVersionId
              AND valid_from <= :at
              AND (valid_to IS NULL OR valid_to > :at)
            ORDER BY case_key
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public PostgresDatasetVersionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertDatasetVersion(DatasetVersion version) {
        jdbc.sql(INSERT_DATASET_SQL)
                .param("id", version.id())
                .param("source", version.source())
                .param("name", version.name())
                .param("version", version.version())
                .param("sourceUri", version.sourceUri())
                .param("license", version.license())
                .param("accessClass", version.accessClass())
                .param("contentDigest", version.contentDigest().value())
                .param("adapterVersion", version.adapterVersion())
                .param("importedAt", Timestamp.from(version.importedAt()))
                .param("sourceClass", version.sourceClass().name())
                .param("partitionClass", version.partitionClass().name())
                .param("scenarioFamilyDigest", version.scenarioFamilyDigest().value())
                .update();
    }

    @Override
    public boolean insertCaseVersion(CaseVersion version) {
        try {
            jdbc.sql(INSERT_CASE_SQL)
                    .param("id", version.id())
                    .param("datasetVersionId", version.datasetVersionId())
                    .param("caseKey", version.caseKey())
                    .param("scenarioFamilyId", version.scenarioFamilyId())
                    .param("validFrom", Timestamp.from(version.validFrom()))
                    .param("validTo", version.validTo() == null ? null : Timestamp.from(version.validTo()))
                    .param("contentDigest", version.contentDigest().value())
                    .param("payload", writePayload(version))
                    .param("sourceArtifactRef", version.sourceArtifactRef())
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<DatasetVersion> findDataset(String name, String version) {
        return jdbc.sql(FIND_DATASET_SQL)
                .param("name", name)
                .param("version", version)
                .query((rs, rowNum) -> new DatasetVersion(
                        rs.getObject("id", UUID.class),
                        rs.getString("source"),
                        rs.getString("name"),
                        rs.getString("version"),
                        rs.getString("source_uri"),
                        rs.getString("license"),
                        rs.getString("access_class"),
                        new Digest(rs.getString("content_digest")),
                        rs.getString("adapter_version"),
                        rs.getTimestamp("imported_at").toInstant(),
                        SourceClass.valueOf(rs.getString("source_class")),
                        PartitionClass.valueOf(rs.getString("partition_class")),
                        new Digest(rs.getString("scenario_family_digest"))))
                .optional();
    }

    @Override
    public List<CaseVersion> findCasesValidAt(UUID datasetVersionId, Instant at) {
        Timestamp atTs = Timestamp.from(at);
        return jdbc.sql(FIND_CASES_VALID_AT_SQL)
                .param("datasetVersionId", datasetVersionId)
                .param("at", atTs)
                .query((rs, rowNum) -> {
                    try {
                        return new CaseVersion(
                                rs.getObject("id", UUID.class),
                                rs.getObject("dataset_version_id", UUID.class),
                                rs.getString("case_key"),
                                rs.getString("scenario_family_id"),
                                rs.getTimestamp("valid_from").toInstant(),
                                ts(rs, "valid_to"),
                                new Digest(rs.getString("content_digest")),
                                rs.getString("source_artifact_ref"),
                                JSON.readValue(rs.getString("payload"), EvalCaseV1.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("case_version.payload 反序列化失败", e);
                    }
                })
                .list();
    }

    private String writePayload(CaseVersion version) {
        try {
            return JSON.writeValueAsString(version.caseContent());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("case_version.payload 序列化失败", e);
        }
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
