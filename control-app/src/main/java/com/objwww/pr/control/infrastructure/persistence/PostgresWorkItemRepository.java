package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.shared.WorkItemState;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * WorkItemRepository 的 Postgres 实现。
 * transitionIfLeaseCurrent 是 I11 晚到结果栅栏的落点：单句条件 UPDATE，
 * 租约不匹配（owner/epoch 已变）时 0 行，调用方只记 STALE 不推进。
 *
 * <p>I17：一切租约/过期比较与租约窗口计算走 DB now()/make_interval，
 * 不接受应用侧时钟参数（INC-30/TB-07）；fake 侧以可设置时钟模拟同一语义。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresWorkItemRepository implements WorkItemRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO work_item (
                id, review_run_id, step_id, work_type, state, priority, available_at,
                lease_owner, lease_until, lease_epoch,
                attempt_count, max_attempts, created_at, updated_at
            ) VALUES (
                :id, :reviewRunId, :stepId, :workType, :state, :priority, :availableAt,
                :leaseOwner, :leaseUntil, :leaseEpoch,
                :attemptCount, :maxAttempts, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state         = EXCLUDED.state,
                available_at  = EXCLUDED.available_at,
                lease_owner   = EXCLUDED.lease_owner,
                lease_until   = EXCLUDED.lease_until,
                lease_epoch   = EXCLUDED.lease_epoch,
                attempt_count = EXCLUDED.attempt_count,
                updated_at    = EXCLUDED.updated_at
            """;

    private static final String LEASE_GUARDED_UPDATE_SQL = """
            UPDATE work_item
               SET state        = :to,
                   available_at = COALESCE(:availableAt, available_at),
                   updated_at   = now()
             WHERE id = :id
               AND lease_owner = :leaseOwner
               AND lease_epoch = :leaseEpoch
            """;

    private static final String CANCEL_ACTIVE_SQL = """
            UPDATE work_item
               SET state = 'CANCELLED', updated_at = now()
             WHERE review_run_id = :reviewRunId
               AND state IN ('READY', 'LEASED', 'RETRY_WAIT')
            """;

    // 领取：单语句原子完成 SKIP LOCKED 选行 + 租约写入（等价"短事务 SELECT→UPDATE→COMMIT"）。
    // lease_until = DB now() + min(step.timeout_seconds, maxLeaseSeconds)（join run_step 取上限）
    private static final String CLAIM_SQL = """
            UPDATE work_item wi
               SET state         = 'LEASED',
                   lease_owner   = :owner,
                   lease_until   = now() + make_interval(secs => LEAST(
                       (SELECT rs.timeout_seconds FROM run_step rs WHERE rs.id = wi.step_id),
                       CAST(:maxLeaseSeconds AS integer))),
                   lease_epoch   = wi.lease_epoch + 1,
                   attempt_count = wi.attempt_count + 1,
                   updated_at    = now()
             WHERE wi.id = (
                     SELECT id FROM work_item
                      WHERE state IN ('READY', 'RETRY_WAIT')
                        AND available_at <= now()
                        AND attempt_count < max_attempts
                      ORDER BY priority DESC, available_at, created_at
                      LIMIT 1
                      FOR UPDATE SKIP LOCKED)
             RETURNING id
            """;

    private static final String HEARTBEAT_SQL = """
            UPDATE work_item
               SET lease_until = now() + make_interval(secs => :leaseSeconds),
                   updated_at  = now()
             WHERE id = :id
               AND lease_owner = :leaseOwner
               AND lease_epoch = :leaseEpoch
               AND state = 'LEASED'
            """;

    private static final String EXPIRED_LEASES_SQL = """
            SELECT id, review_run_id, step_id, work_type, state, priority, available_at,
                   lease_owner, lease_until, lease_epoch,
                   attempt_count, max_attempts, created_at, updated_at
              FROM work_item
             WHERE state = 'LEASED' AND lease_until < now()
             ORDER BY lease_until
             LIMIT :limit
            """;

    private static final String RECLAIM_SQL = """
            UPDATE work_item
               SET state        = :target,
                   lease_owner  = NULL,
                   lease_until  = NULL,
                   lease_epoch  = lease_epoch + 1,
                   available_at = now(),
                   updated_at   = now()
             WHERE id = :id
               AND state = 'LEASED'
               AND lease_epoch = :leaseEpoch
               AND lease_until < now()
            """;

    private static final String SELECT_COLUMNS = """
            id, review_run_id, step_id, work_type, state, priority, available_at,
            lease_owner, lease_until, lease_epoch,
            attempt_count, max_attempts, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public PostgresWorkItemRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(WorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem");
        jdbc.sql(UPSERT_SQL)
                .param("id", workItem.getId())
                .param("reviewRunId", workItem.getReviewRunId())
                .param("stepId", workItem.getStepId())
                .param("workType", workItem.getWorkType())
                .param("state", workItem.getState().name())
                .param("priority", workItem.getPriority())
                .param("availableAt", Timestamp.from(workItem.getAvailableAt()))
                .param("leaseOwner", workItem.getLeaseOwner())
                .param("leaseUntil",
                        workItem.getLeaseUntil() == null ? null : Timestamp.from(workItem.getLeaseUntil()))
                .param("leaseEpoch", workItem.getLeaseEpoch())
                .param("attemptCount", workItem.getAttemptCount())
                .param("maxAttempts", workItem.getMaxAttempts())
                .param("createdAt", Timestamp.from(workItem.getCreatedAt()))
                .param("updatedAt", Timestamp.from(workItem.getUpdatedAt()))
                .update();
    }

    @Override
    public Optional<WorkItem> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM work_item WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<WorkItem> findByStepId(UUID stepId) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM work_item WHERE step_id = :stepId")
                .param("stepId", Objects.requireNonNull(stepId))
                .query(this::map)
                .optional();
    }

    @Override
    public boolean transitionIfLeaseCurrent(UUID id, String leaseOwner, long leaseEpoch,
                                            WorkItemState to, Instant availableAt) {
        int updated = jdbc.sql(LEASE_GUARDED_UPDATE_SQL)
                .param("to", Objects.requireNonNull(to).name())
                .param("availableAt", availableAt == null ? null : Timestamp.from(availableAt))
                .param("id", Objects.requireNonNull(id))
                .param("leaseOwner", Objects.requireNonNull(leaseOwner))
                .param("leaseEpoch", leaseEpoch)
                .update();
        return updated == 1;
    }

    @Override
    public int cancelActiveByRunId(UUID reviewRunId) {
        return jdbc.sql(CANCEL_ACTIVE_SQL)
                .param("reviewRunId", Objects.requireNonNull(reviewRunId))
                .update();
    }

    @Override
    public Optional<WorkItem> claimNext(String owner, int maxLeaseSeconds) {
        Optional<UUID> claimedId = jdbc.sql(CLAIM_SQL)
                .param("owner", Objects.requireNonNull(owner))
                .param("maxLeaseSeconds", maxLeaseSeconds)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();
        return claimedId.flatMap(this::findById);
    }

    @Override
    public boolean heartbeat(UUID id, String leaseOwner, long leaseEpoch, int leaseSeconds) {
        int updated = jdbc.sql(HEARTBEAT_SQL)
                .param("leaseSeconds", leaseSeconds)
                .param("id", Objects.requireNonNull(id))
                .param("leaseOwner", Objects.requireNonNull(leaseOwner))
                .param("leaseEpoch", leaseEpoch)
                .update();
        return updated == 1;
    }

    @Override
    public List<WorkItem> findExpiredLeases(int limit) {
        return jdbc.sql(EXPIRED_LEASES_SQL)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public boolean reclaimExpiredLease(UUID id, long leaseEpoch, WorkItemState target) {
        if (target != WorkItemState.READY && target != WorkItemState.DEAD) {
            throw new IllegalArgumentException("回收目标态只能为 READY/DEAD: " + target);
        }
        int updated = jdbc.sql(RECLAIM_SQL)
                .param("target", target.name())
                .param("id", Objects.requireNonNull(id))
                .param("leaseEpoch", leaseEpoch)
                .update();
        return updated == 1;
    }

    private WorkItem map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new WorkItem(
                rs.getObject("id", UUID.class),
                rs.getObject("review_run_id", UUID.class),
                rs.getObject("step_id", UUID.class),
                rs.getString("work_type"),
                WorkItemState.valueOf(rs.getString("state")),
                rs.getInt("priority"),
                rs.getTimestamp("available_at").toInstant(),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
