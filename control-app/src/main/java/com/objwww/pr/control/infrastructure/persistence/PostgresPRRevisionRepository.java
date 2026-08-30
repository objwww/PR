package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RevisionFingerprint;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PRRevisionRepository 的 Postgres 实现：只插不更（I12：同 fingerprint 复用行，不覆盖；
 * UPDATE/DELETE 由 DB trigger 拒绝，I9）。构造时 digest 已就绪（评审修正 #3），一次性插完整行。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresPRRevisionRepository implements PRRevisionRepository {

    private static final String INSERT_SQL = """
            INSERT INTO pr_revision (
                id, pr_subject_id, head_sha, base_ref, base_sha, merge_base_sha,
                diff_digest, source_snapshot_digest, revision_fingerprint,
                observed_at, created_at
            ) VALUES (
                :id, :prSubjectId, :headSha, :baseRef, :baseSha, :mergeBaseSha,
                :diffDigest, :sourceSnapshotDigest, :revisionFingerprint,
                :observedAt, :createdAt
            )
            """;

    private static final String SELECT_COLUMNS = """
            id, pr_subject_id, head_sha, base_ref, base_sha, merge_base_sha,
            diff_digest, source_snapshot_digest, revision_fingerprint,
            observed_at, created_at
            """;

    private final JdbcClient jdbc;

    public PostgresPRRevisionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(PRRevision revision) {
        Objects.requireNonNull(revision, "revision");
        jdbc.sql(INSERT_SQL)
                .param("id", revision.getId())
                .param("prSubjectId", revision.getPrSubjectId())
                .param("headSha", revision.getHeadSha())
                .param("baseRef", revision.getBaseRef())
                .param("baseSha", revision.getBaseSha())
                .param("mergeBaseSha", revision.getMergeBaseSha())
                .param("diffDigest", revision.getDiffDigest().value())
                .param("sourceSnapshotDigest",
                        revision.getSourceSnapshotDigest() == null
                                ? null : revision.getSourceSnapshotDigest().value())
                .param("revisionFingerprint", revision.getRevisionFingerprint().value())
                .param("observedAt", Timestamp.from(revision.getObservedAt()))
                .param("createdAt", Timestamp.from(revision.getCreatedAt()))
                .update();
    }

    @Override
    public Optional<PRRevision> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM pr_revision WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<PRRevision> findByFingerprint(UUID prSubjectId, RevisionFingerprint fingerprint) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + """
                        FROM pr_revision
                        WHERE pr_subject_id = :prSubjectId AND revision_fingerprint = :fingerprint
                        """)
                .param("prSubjectId", Objects.requireNonNull(prSubjectId))
                .param("fingerprint", Objects.requireNonNull(fingerprint).value())
                .query(this::map)
                .optional();
    }

    private PRRevision map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String snapshotDigest = rs.getString("source_snapshot_digest");
        return new PRRevision(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_subject_id", UUID.class),
                rs.getString("head_sha"),
                rs.getString("base_ref"),
                rs.getString("base_sha"),
                rs.getString("merge_base_sha"),
                new Digest(rs.getString("diff_digest")),
                snapshotDigest == null ? null : new Digest(snapshotDigest),
                new RevisionFingerprint(rs.getString("revision_fingerprint")),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
