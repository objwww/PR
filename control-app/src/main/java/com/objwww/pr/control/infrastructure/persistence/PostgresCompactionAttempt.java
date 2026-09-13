package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.CompactionAttempt;
import com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_compaction_attempt 的 Postgres 实现（CL-07，V102）：insert-if-absent
 * 撞 uq(task, source, policy, coalesce(config_epoch,-1)) → 回读胜者行；
 * casState 以 id+fromState 条件写（影响行数即 CAS 结果）。
 */
public class PostgresCompactionAttempt implements CompactionAttemptPort {

    private static final String COLUMNS = """
            id, run_id, task_id, source_context_digest, policy_digest,
            owner, lease_epoch, config_epoch, expected_revision, state,
            logical_action_key, error_code, summary_id, created_at, settled_at
            """;

    private final JdbcClient jdbc;

    public PostgresCompactionAttempt(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public CompactionAttempt insertIfAbsent(CompactionAttempt candidate) {
        try {
            jdbc.sql("""
                    insert into rca_compaction_attempt (id, run_id, task_id,
                        source_context_digest, policy_digest, owner, lease_epoch,
                        config_epoch, expected_revision, state, logical_action_key,
                        error_code, summary_id, created_at, settled_at)
                    values (:id, :runId, :taskId, :source, :policy, :owner,
                        :leaseEpoch, :configEpoch, :expectedRevision, :state,
                        :logicalActionKey, :errorCode, :summaryId, :createdAt,
                        :settledAt)
                    """)
                    .param("id", candidate.id())
                    .param("runId", candidate.runId())
                    .param("taskId", candidate.taskId())
                    .param("source", candidate.sourceContextDigest())
                    .param("policy", candidate.policyDigest())
                    .param("owner", candidate.owner())
                    .param("leaseEpoch", candidate.leaseEpoch())
                    .param("configEpoch", candidate.configEpoch())
                    .param("expectedRevision", candidate.expectedRevision())
                    .param("state", candidate.state())
                    .param("logicalActionKey", candidate.logicalActionKey())
                    .param("errorCode", candidate.errorCode())
                    .param("summaryId", candidate.summaryId())
                    .param("createdAt", Timestamp.from(candidate.createdAt()))
                    .param("settledAt",
                            candidate.settledAt() == null ? null
                                    : Timestamp.from(candidate.settledAt()))
                    .update();
            return candidate;
        } catch (DuplicateKeyException e) {
            return findByLogicalKey(candidate.taskId(), candidate.sourceContextDigest(),
                    candidate.policyDigest(), candidate.configEpoch())
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public boolean casState(UUID id, String fromState, String toState,
            String errorCode, UUID summaryId) {
        // V102 ck_settle：非终态（RESERVED/IN_FLIGHT）settled_at 必空——预留→在飞
        // 迁移不得落结算时间，只有终态迁移才 now() 结算（真机 IT 实证曾违约束）
        boolean settling = !CompactionAttempt.RESERVED.equals(toState)
                && !CompactionAttempt.IN_FLIGHT.equals(toState);
        return jdbc.sql("""
                update rca_compaction_attempt
                set state = :toState, error_code = :errorCode,
                    summary_id = :summaryId,
                    settled_at = case when :settling then now() else null end
                where id = :id and state = :fromState
                """)
                .param("toState", toState)
                .param("errorCode", errorCode)
                .param("summaryId", summaryId)
                .param("settling", settling)
                .param("id", id)
                .param("fromState", fromState)
                .update() == 1;
    }

    @Override
    public Optional<CompactionAttempt> findById(UUID id) {
        List<CompactionAttempt> rows = jdbc.sql(
                        "select " + COLUMNS + " from rca_compaction_attempt where id = :id")
                .param("id", id).query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<CompactionAttempt> findByLogicalKey(UUID taskId, String source,
            String policy, Long configEpoch) {
        List<CompactionAttempt> rows = jdbc.sql("select " + COLUMNS
                        + " from rca_compaction_attempt where task_id = :taskId"
                        + " and source_context_digest = :source"
                        + " and policy_digest = :policy"
                        + " and coalesce(config_epoch, -1) = coalesce(cast(:configEpoch as bigint), -1)")
                .param("taskId", taskId).param("source", source).param("policy", policy)
                .param("configEpoch", configEpoch)
                .query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private CompactionAttempt mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Long leaseEpoch = (Long) rs.getObject("lease_epoch");
        Long configEpoch = (Long) rs.getObject("config_epoch");
        Timestamp settledAt = rs.getTimestamp("settled_at");
        return new CompactionAttempt(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("source_context_digest"),
                rs.getString("policy_digest"),
                rs.getString("owner"), leaseEpoch, configEpoch,
                rs.getLong("expected_revision"), rs.getString("state"),
                rs.getString("logical_action_key"), rs.getString("error_code"),
                rs.getObject("summary_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                settledAt == null ? null : settledAt.toInstant());
    }
}
