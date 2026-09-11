package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.model.ReviewAssignment;
import com.objwww.pr.control.eval.domain.repository.ReviewAssignmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReviewAssignmentRepository} 的 Postgres 实现（EV-08/V87；JdbcClient 手写
 * SQL，沿 PostgresEvalRunCommandRepository 惯例）。
 *
 * <p>写面全部单行条件 UPDATE（CAS）：
 * <ul>
 *   <li>claim 谓词 = status='PENDING' OR (IN_PROGRESS 且 lease_expires_at &lt;= now)
 *       ——惰性回收：超时租约不发行回收 UPDATE，领取谓词直接覆盖回收面（EU29
 *       双人同领行级锁只可能一人赢，行数 1/0 即胜负）；</li>
 *   <li>submit 谓词 = id + reviewer + status='IN_PROGRESS' + revision 四锚——
 *       租约被回收重领后 revision 已 +1，旧持有者提交必撞（不静默覆盖）。</li>
 * </ul>
 * 授权面（V87）：control_app select,insert + 列级 update 六列——本类 UPDATE
 * 只触碰 reviewer/status/revision/claimed_at/lease_expires_at/submitted_at。
 */
public class PostgresReviewAssignmentRepository implements ReviewAssignmentRepository {

    private final JdbcClient jdbc;

    public PostgresReviewAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(ReviewAssignment a) {
        jdbc.sql("""
                        insert into review_assignment (
                            id, run_id, case_execution_id, status, reviewer, revision,
                            claimed_at, lease_expires_at, submitted_at, created_by, created_at
                        ) values (
                            :id, :runId, :caseId, :status, :reviewer, :revision,
                            :claimedAt, :leaseExpiresAt, :submittedAt, :createdBy, :createdAt
                        )
                        """)
                .param("id", a.id())
                .param("runId", a.runId())
                .param("caseId", a.caseExecutionId())
                .param("status", a.status().name())
                .param("reviewer", a.reviewer())
                .param("revision", a.revision())
                .param("claimedAt", a.claimedAt() == null ? null : Timestamp.from(a.claimedAt()))
                .param("leaseExpiresAt",
                        a.leaseExpiresAt() == null ? null : Timestamp.from(a.leaseExpiresAt()))
                .param("submittedAt",
                        a.submittedAt() == null ? null : Timestamp.from(a.submittedAt()))
                .param("createdBy", a.createdBy())
                .param("createdAt", Timestamp.from(a.createdAt()))
                .update();
    }

    @Override
    public Optional<ReviewAssignment> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(this::map).optional();
    }

    @Override
    public boolean claim(UUID id, String reviewer, Instant claimedAt, Instant leaseExpiresAt,
                         Instant now) {
        return jdbc.sql("""
                        update review_assignment
                        set reviewer = :reviewer, status = 'IN_PROGRESS',
                            claimed_at = :claimedAt, lease_expires_at = :leaseExpiresAt,
                            revision = revision + 1
                        where id = :id
                          and (status = 'PENDING'
                               or (status = 'IN_PROGRESS' and lease_expires_at <= :now))
                        """)
                .param("id", id)
                .param("reviewer", reviewer)
                .param("claimedAt", Timestamp.from(claimedAt))
                .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public boolean submit(UUID id, String reviewer, int expectedRevision,
                          Instant submittedAt) {
        return jdbc.sql("""
                        update review_assignment
                        set status = 'SUBMITTED', submitted_at = :submittedAt,
                            revision = revision + 1
                        where id = :id and reviewer = :reviewer
                          and status = 'IN_PROGRESS' and revision = :expectedRevision
                        """)
                .param("id", id)
                .param("reviewer", reviewer)
                .param("expectedRevision", expectedRevision)
                .param("submittedAt", Timestamp.from(submittedAt))
                .update() == 1;
    }

    @Override
    public AssignmentPage listByRun(UUID runId, String reviewer,
                                    ReviewAssignment.Status statusFilter, Instant now,
                                    KeysetCursor cursor, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", runId);
        params.put("now", Timestamp.from(now));
        String where = " where run_id = :runId";
        if (reviewer != null && !reviewer.isBlank()) {
            where += " and reviewer = :reviewer";
            params.put("reviewer", reviewer);
        }
        if (statusFilter != null) {
            // 有效状态过滤：PENDING 含租约超时的 IN_PROGRESS（惰性回收投影）
            where += switch (statusFilter) {
                case PENDING -> " and (status = 'PENDING'"
                        + " or (status = 'IN_PROGRESS' and lease_expires_at <= :now))";
                case IN_PROGRESS -> " and status = 'IN_PROGRESS' and lease_expires_at > :now";
                case SUBMITTED -> " and status = 'SUBMITTED'";
            };
        }
        if (cursor != null) {
            where += " and (created_at, id) < (:cursorAt, cast(:cursorId as uuid))";
            params.put("cursorAt", Timestamp.from(cursor.createdAt()));
            params.put("cursorId", cursor.id().toString());
        }
        params.put("lim", limit + 1);
        List<ReviewAssignment> rows = new ArrayList<>(jdbc.sql(
                        SELECT + where + " order by created_at desc, id desc limit :lim")
                .params(params)
                .query(this::map).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new AssignmentPage(rows, hasMore);
    }

    @Override
    public List<ReviewAssignment> listAllByRun(UUID runId) {
        return jdbc.sql(SELECT + " where run_id = :runId order by created_at asc, id asc")
                .param("runId", runId)
                .query(this::map).list();
    }

    @Override
    public int countOpenByCase(UUID runId, UUID caseExecutionId, Instant now) {
        return jdbc.sql("""
                        select count(*) from review_assignment
                        where run_id = :runId and case_execution_id = :caseId
                          and (status = 'PENDING'
                               or (status = 'IN_PROGRESS' and lease_expires_at > :now))
                        """)
                .param("runId", runId)
                .param("caseId", caseExecutionId)
                .param("now", Timestamp.from(now))
                .query(Long.class).single().intValue();
    }

    private static final String SELECT =
            "select id, run_id, case_execution_id, status, reviewer, revision,"
                    + " claimed_at, lease_expires_at, submitted_at, created_by, created_at"
                    + " from review_assignment";

    private ReviewAssignment map(ResultSet rs, int i) throws SQLException {
        Timestamp claimed = rs.getTimestamp("claimed_at");
        Timestamp lease = rs.getTimestamp("lease_expires_at");
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new ReviewAssignment(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("case_execution_id", UUID.class),
                ReviewAssignment.Status.valueOf(rs.getString("status")),
                rs.getString("reviewer"), rs.getInt("revision"),
                claimed == null ? null : claimed.toInstant(),
                lease == null ? null : lease.toInstant(),
                submitted == null ? null : submitted.toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }
}
