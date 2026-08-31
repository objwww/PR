package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PRSubjectRepository 的 Postgres 实现。
 * save 是 INSERT / ON CONFLICT (id) UPDATE 投影字段的 upsert；
 * publication_epoch / next_outbox_sequence / last_resolved_sequence 一律不经 save 改写——
 * epoch 只走 switchRevisionAndBumpEpoch（v2.2 §3-2），sequence 只走 PostgresSequenceAllocator。
 * last_event_updated_at 同样不经 save 读写（INSERT 默认 NULL、ON CONFLICT 不动它）——
 * 水印只走 advanceWatermarkIfNewer 的 GREATEST 条件更新（I10：投影不被陈旧事件回退）。
 * next_pr_reconcile_at / pr_reconcile_error_count（M1-T07）同理不经 save：
 * INSERT 吃 V3 默认值（now()/0），ON CONFLICT 不动；只走 markReconciled /
 * markReconcileError 的单句 UPDATE（时刻一律 DB now() + make_interval，I17）。
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

    /**
     * LWW 水印推进（I10/CT-14）：GREATEST 在 DB 侧求 max，并发两 T1 旧值不覆新值；
     * Postgres GREATEST 忽略 NULL 参数，旧水印 NULL 时直接采纳新值。
     */
    private static final String ADVANCE_WATERMARK_SQL = """
            UPDATE pr_subject
               SET last_event_updated_at = GREATEST(last_event_updated_at, :eventUpdatedAt),
                   version               = version + 1,
                   updated_at            = :now
             WHERE id = :id
            """;

    /** T-close / T-draft（I15）：投影刷新与 epoch+1 同句原子（修正 #5） */
    private static final String REFRESH_STATE_AND_BUMP_EPOCH_SQL = """
            UPDATE pr_subject
               SET state             = :state,
                   draft             = :draft,
                   merged            = :merged,
                   publication_epoch = publication_epoch + 1,
                   version           = version + 1,
                   updated_at        = :now
             WHERE id = :id
            """;

    private static final String SELECT_COLUMNS = """
            id, github_installation_id, github_repository_id, repository_full_name, pr_number,
            state, draft, merged, current_revision_id, current_policy_version,
            publication_epoch, next_outbox_sequence, last_resolved_sequence,
            last_event_updated_at, next_pr_reconcile_at, pr_reconcile_error_count,
            version, created_at, updated_at
            """;

    /** §4.5 公平扫描：最久未查的先查（ORDER BY 同列），LIMIT=API 预算不饿死尾部（E2E-14） */
    private static final String FIND_DUE_FOR_RECONCILE_SQL = """
            SELECT %s FROM pr_subject
             WHERE state = 'OPEN' AND next_pr_reconcile_at <= now()
             ORDER BY next_pr_reconcile_at
             LIMIT :limit
            """;

    /** 对账成功：排下一轮 + 失败计数清零（I17：时刻由 DB now() 计算） */
    private static final String MARK_RECONCILED_SQL = """
            UPDATE pr_subject
               SET next_pr_reconcile_at      = now() + make_interval(secs => :intervalSeconds),
                   pr_reconcile_error_count = 0,
                   version                  = version + 1,
                   updated_at               = now()
             WHERE id = :id
            """;

    /** 对账失败：计数+1 + 退避排下一跳；RETURNING 新计数供 ReconcilerDegraded 阈值判定（EX-12） */
    private static final String MARK_RECONCILE_ERROR_SQL = """
            UPDATE pr_subject
               SET pr_reconcile_error_count = pr_reconcile_error_count + 1,
                   next_pr_reconcile_at     = now() + make_interval(secs => :backoffSeconds),
                   version                  = version + 1,
                   updated_at               = now()
             WHERE id = :id
            RETURNING pr_reconcile_error_count
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
    public void advanceWatermarkIfNewer(UUID id, Instant eventUpdatedAt, Instant now) {
        int updated = jdbc.sql(ADVANCE_WATERMARK_SQL)
                .param("eventUpdatedAt", Timestamp.from(Objects.requireNonNull(eventUpdatedAt,
                        "eventUpdatedAt 为 null 时调用方应跳过本方法（EX-18 缺值不覆盖）")))
                .param("now", Timestamp.from(Objects.requireNonNull(now)))
                .param("id", Objects.requireNonNull(id))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("pr_subject 水印推进影响行数异常: " + updated + ", id=" + id);
        }
    }

    @Override
    public void refreshStateAndBumpEpoch(UUID id, PrSubjectState state, boolean draft, boolean merged,
                                         Instant now) {
        int updated = jdbc.sql(REFRESH_STATE_AND_BUMP_EPOCH_SQL)
                .param("state", Objects.requireNonNull(state).name())
                .param("draft", draft)
                .param("merged", merged)
                .param("now", Timestamp.from(Objects.requireNonNull(now)))
                .param("id", Objects.requireNonNull(id))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("pr_subject T-close/T-draft 更新影响行数异常: " + updated + ", id=" + id);
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

    @Override
    public List<PRSubject> findDueForReconcile(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 >= 1: " + limit);
        }
        return jdbc.sql(FIND_DUE_FOR_RECONCILE_SQL.formatted(SELECT_COLUMNS))
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public void markReconciled(UUID id, java.time.Duration interval) {
        int updated = jdbc.sql(MARK_RECONCILED_SQL)
                .param("intervalSeconds", Objects.requireNonNull(interval).toSeconds())
                .param("id", Objects.requireNonNull(id))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("pr_subject 对账成功回写影响行数异常: " + updated + ", id=" + id);
        }
    }

    @Override
    public int markReconcileError(UUID id, java.time.Duration backoff) {
        return jdbc.sql(MARK_RECONCILE_ERROR_SQL)
                .param("backoffSeconds", Objects.requireNonNull(backoff).toSeconds())
                .param("id", Objects.requireNonNull(id))
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("pr_subject 对账失败回写 0 行, id=" + id));
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
                rs.getTimestamp("last_event_updated_at") == null
                        ? null : rs.getTimestamp("last_event_updated_at").toInstant(),
                rs.getTimestamp("next_pr_reconcile_at").toInstant(),
                rs.getInt("pr_reconcile_error_count"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
