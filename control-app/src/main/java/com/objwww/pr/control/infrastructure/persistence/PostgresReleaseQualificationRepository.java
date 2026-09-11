package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * release_qualification 的 Postgres 实现（V61；EN-02）。证明行 insert-only，
 * 撤销 = UPDATE 仅 revoked_* 三列（DB 面零 delete 授权）。读路径经域构造期
 * 重校验（含撤销三件套一致性，防御纵深同 PostgresConfigBundleRepository）。
 */
public class PostgresReleaseQualificationRepository implements ReleaseQualificationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO release_qualification (
                id, candidate_digest, baseline_digest, dataset_manifest_digest,
                runner_version, grader_version, quality_verdict, usage_status,
                granted_scope, granted_by, granted_at, revoked_at, revoked_by, revoked_reason
            ) VALUES (
                :id, :candidate, :baseline, :dataset,
                :runner, :grader, :verdict, :usage,
                :scope, :grantedBy, :grantedAt, :revokedAt, :revokedBy, :revokedReason
            )
            """;

    private static final String FIND_UNREVOKED_SQL = """
            SELECT id, candidate_digest, baseline_digest, dataset_manifest_digest,
                   runner_version, grader_version, quality_verdict, usage_status,
                   granted_scope, granted_by, granted_at, revoked_at, revoked_by, revoked_reason
              FROM release_qualification
             WHERE candidate_digest = :candidate AND revoked_at IS NULL
             ORDER BY granted_at DESC
             LIMIT 1
            """;

    private static final String REVOKE_SQL = """
            UPDATE release_qualification
               SET revoked_at = :at, revoked_by = :by, revoked_reason = :reason
             WHERE id = :id AND revoked_at IS NULL
            """;

    private final JdbcClient jdbc;

    public PostgresReleaseQualificationRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource 不得为 null"));
    }

    @Override
    public boolean insert(ReleaseQualification qualification) {
        jdbc.sql(INSERT_SQL)
                .param("id", qualification.id())
                .param("candidate", qualification.candidateDigest().hex())
                .param("baseline", qualification.baselineDigest() == null
                        ? null : qualification.baselineDigest().hex())
                .param("dataset", qualification.datasetManifestDigest())
                .param("runner", qualification.runnerVersion())
                .param("grader", qualification.graderVersion())
                .param("verdict", qualification.qualityVerdict())
                .param("usage", qualification.usageStatus())
                .param("scope", qualification.grantedScope())
                .param("grantedBy", qualification.grantedBy())
                .param("grantedAt", Timestamp.from(qualification.grantedAt()))
                .param("revokedAt", qualification.revokedAt() == null
                        ? null : Timestamp.from(qualification.revokedAt()))
                .param("revokedBy", qualification.revokedBy())
                .param("revokedReason", qualification.revokedReason())
                .update();
        return true;
    }

    @Override
    public Optional<ReleaseQualification> findUnrevokedFor(Digest candidate) {
        return jdbc.sql(FIND_UNREVOKED_SQL)
                .param("candidate", candidate.hex())
                .query((rs, i) -> new ReleaseQualification(
                        rs.getObject("id", UUID.class),
                        new Digest(rs.getString("candidate_digest")),
                        rs.getString("baseline_digest") == null
                                ? null : new Digest(rs.getString("baseline_digest")),
                        rs.getString("dataset_manifest_digest"),
                        rs.getString("runner_version"),
                        rs.getString("grader_version"),
                        rs.getString("quality_verdict"),
                        rs.getString("usage_status"),
                        rs.getString("granted_scope"),
                        rs.getString("granted_by"),
                        rs.getTimestamp("granted_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null
                                ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getString("revoked_by"),
                        rs.getString("revoked_reason")))
                .optional();
    }

    @Override
    public boolean revoke(UUID id, String by, String reason, Instant at) {
        Integer updated = jdbc.sql(REVOKE_SQL)
                .param("id", id)
                .param("by", by)
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .update();
        return updated != null && updated == 1;
    }
}
