package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.ReviewFinding;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FindingState;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ReviewFindingRepository 的 Postgres 实现。
 * insert 幂等：ON CONFLICT (review_run_id, fingerprint) DO NOTHING——T2 重放（重试/重领）
 * 重复登记同 fingerprint 是空操作，不产生重复行（M5 行级评论幂等的挂载点，v2.2 §5）。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresReviewFindingRepository implements ReviewFindingRepository {

    private static final String INSERT_SQL = """
            INSERT INTO review_finding (
                id, review_run_id, pr_revision_id,
                fingerprint, rule_id, severity, file_path, line_start, line_end,
                body_artifact_digest, state, created_at
            ) VALUES (
                :id, :reviewRunId, :prRevisionId,
                :fingerprint, :ruleId, :severity, :filePath, :lineStart, :lineEnd,
                :bodyArtifactDigest, :state, :createdAt
            )
            ON CONFLICT (review_run_id, fingerprint) DO NOTHING
            """;

    private static final String SELECT_COLUMNS = """
            id, review_run_id, pr_revision_id,
            fingerprint, rule_id, severity, file_path, line_start, line_end,
            body_artifact_digest, state, created_at
            """;

    private final JdbcClient jdbc;

    public PostgresReviewFindingRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(ReviewFinding finding) {
        Objects.requireNonNull(finding, "finding");
        jdbc.sql(INSERT_SQL)
                .param("id", finding.getId())
                .param("reviewRunId", finding.getReviewRunId())
                .param("prRevisionId", finding.getPrRevisionId())
                .param("fingerprint", finding.getFingerprint().value())
                .param("ruleId", finding.getRuleId())
                .param("severity", finding.getSeverity())
                .param("filePath", finding.getFilePath())
                .param("lineStart", finding.getLineStart())
                .param("lineEnd", finding.getLineEnd())
                .param("bodyArtifactDigest",
                        finding.getBodyArtifactDigest() == null ? null : finding.getBodyArtifactDigest().value())
                .param("state", finding.getState().name())
                .param("createdAt", Timestamp.from(finding.getCreatedAt()))
                .update();
    }

    @Override
    public List<ReviewFinding> findByRunId(UUID reviewRunId) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + """
                        FROM review_finding WHERE review_run_id = :reviewRunId ORDER BY created_at
                        """)
                .param("reviewRunId", Objects.requireNonNull(reviewRunId))
                .query(this::map)
                .list();
    }

    private ReviewFinding map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String bodyDigest = rs.getString("body_artifact_digest");
        int lineStart = rs.getInt("line_start");
        boolean lineStartNull = rs.wasNull();
        int lineEnd = rs.getInt("line_end");
        boolean lineEndNull = rs.wasNull();
        return new ReviewFinding(
                rs.getObject("id", UUID.class),
                rs.getObject("review_run_id", UUID.class),
                rs.getObject("pr_revision_id", UUID.class),
                new Digest(rs.getString("fingerprint")),
                rs.getString("rule_id"),
                rs.getString("severity"),
                rs.getString("file_path"),
                lineStartNull ? null : lineStart,
                lineEndNull ? null : lineEnd,
                bodyDigest == null ? null : new Digest(bodyDigest),
                FindingState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant());
    }
}
