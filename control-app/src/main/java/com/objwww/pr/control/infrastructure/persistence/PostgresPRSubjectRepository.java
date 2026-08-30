package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PRSubjectRepository 的 Postgres 实现。
 * save 是 INSERT / ON CONFLICT (id) UPDATE 投影字段的 upsert；
 * publication_epoch / next_outbox_sequence / last_resolved_sequence 一律不经 save 改写——
 * epoch 只走 switchRevisionAndBumpEpoch（v2.2 §3-2），sequence 只走 PostgresSequenceAllocator。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresPRSubjectRepository implements PRSubjectRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO pr_subject (
                id, github_installation_id, github_repository_id, repository_full_name, pr_number,
                state, draft, merged, current_revision_id, current_policy_version,
                publication_epoch, next_outbox_sequence, last_resolved_sequence,
                version, created_at, updated_at
            ) VALUES (
                :id, :installationId, :repositoryId, :repositoryFullName, :prNumber,
                :state, :draft, :merged, :currentRevisionId, :currentPolicyVersion,
                :publicationEpoch, :nextOutboxSequence, :lastResolvedSequence,
                :version, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state                  = EXCLUDED.state,
                draft                  = EXCLUDED.draft,
                merged                 = EXCLUDED.merged,
                current_revision_id    = EXCLUDED.current_revision_id,
                current_policy_version = EXCLUDED.current_policy_version,
                version                = pr_subject.version + 1,
                updated_at             = EXCLUDED.updated_at
            """;

    private static final String SWITCH_REVISION_SQL = """
            UPDATE pr_subject
               SET current_revision_id    = :revisionId,
                   current_policy_version = :policyVersion,
                   publication_epoch      = publication_epoch + 1,
                   version                = version + 1,
                   updated_at             = :now
             WHERE id = :id
            """;

    private static final String SELECT_COLUMNS = """
            id, github_installation_id, github_repository_id, repository_full_name, pr_number,
            state, draft, merged, current_revision_id, current_policy_version,
            publication_epoch, next_outbox_sequence, last_resolved_sequence,
            version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public PostgresPRSubjectRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(PRSubject subject) {
        Objects.requireNonNull(subject, "subject");
        jdbc.sql(UPSERT_SQL)
                .param("id", subject.getId())
                .param("installationId", subject.getGithubInstallationId())
                .param("repositoryId", subject.getGithubRepositoryId())
                .param("repositoryFullName", subject.getRepositoryFullName())
                .param("prNumber", subject.getPrNumber())
                .param("state", subject.getState().name())
                .param("draft", subject.isDraft())
                .param("merged", subject.isMerged())
                .param("currentRevisionId", subject.getCurrentRevisionId())
                .param("currentPolicyVersion", subject.getCurrentPolicyVersion())
                .param("publicationEpoch", subject.getPublicationEpoch())
                .param("nextOutboxSequence", subject.getNextOutboxSequence())
                .param("lastResolvedSequence", subject.getLastResolvedSequence())
                .param("version", subject.getVersion())
                .param("createdAt", Timestamp.from(subject.getCreatedAt()))
                .param("updatedAt", Timestamp.from(subject.getUpdatedAt()))
                .update();
    }

    @Override
    public void switchRevisionAndBumpEpoch(UUID id, UUID revisionId, String policyVersion, Instant now) {
        int updated = jdbc.sql(SWITCH_REVISION_SQL)
                .param("revisionId", Objects.requireNonNull(revisionId))
                .param("policyVersion", Objects.requireNonNull(policyVersion))
                .param("now", Timestamp.from(Objects.requireNonNull(now)))
                .param("id", Objects.requireNonNull(id))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("pr_subject 换届更新影响行数异常: " + updated + ", id=" + id);
        }
    }

    @Override
    public Optional<PRSubject> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM pr_subject WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<PRSubject> findByRepositoryAndPrNumber(long githubRepositoryId, int prNumber) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + """
                        FROM pr_subject
                        WHERE github_repository_id = :repositoryId AND pr_number = :prNumber
                        """)
                .param("repositoryId", githubRepositoryId)
                .param("prNumber", prNumber)
                .query(this::map)
                .optional();
    }

    private PRSubject map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PRSubject(
                rs.getObject("id", UUID.class),
                rs.getLong("github_installation_id"),
                rs.getLong("github_repository_id"),
                rs.getString("repository_full_name"),
                rs.getInt("pr_number"),
                PrSubjectState.valueOf(rs.getString("state")),
                rs.getBoolean("draft"),
                rs.getBoolean("merged"),
                rs.getObject("current_revision_id", UUID.class),
                rs.getString("current_policy_version"),
                rs.getLong("publication_epoch"),
                rs.getLong("next_outbox_sequence"),
                rs.getLong("last_resolved_sequence"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
