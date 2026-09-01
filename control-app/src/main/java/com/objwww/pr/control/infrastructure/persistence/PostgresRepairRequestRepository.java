package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.model.RepairRunOutcome;
import com.objwww.pr.control.domain.repository.RepairRequestRepository;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import com.objwww.pr.shared.RepairRequestState;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PostgresRepairRequestRepository implements RepairRequestRepository {

    private static final String PROJECTION = """
            SELECT rr.id request_id, rr.policy_tier, rr.state repair_state,
                   rr.attempt_count, rr.max_attempts,
                   r.id resource_id, r.resource_type, r.pr_subject_id,
                   o.pr_revision_id, s.current_revision_id,
                   o.review_run_id original_run_id, COALESCE(run.root_run_id, run.id) original_root_run_id,
                   o.operation_id original_operation_id, o.command_type,
                   o.aggregate_key, o.policy_version,
                   latest.payload_hash latest_payload_hash,
                   latest.command_type latest_command_type,
                   latest.operation_id latest_operation_id,
                   COALESCE(base.payload_hash, latest.payload_hash) base_payload_hash
              FROM repair_request rr
              JOIN publication_resource r ON r.id = rr.publication_resource_id
              JOIN outbox_command o ON o.operation_id = r.created_by_operation_id
              JOIN review_run run ON run.id = o.review_run_id
              JOIN pr_subject s ON s.id = r.pr_subject_id
              JOIN LATERAL (
                    SELECT x.operation_id, x.command_type, x.payload_hash
                      FROM outbox_command x
                     WHERE x.pr_subject_id = r.pr_subject_id
                       AND x.pr_revision_id = o.pr_revision_id
                       AND x.state = 'CONFIRMED'
                       AND ((r.resource_type = 'CHECK_RUN' AND x.command_type IN ('CREATE_CHECK','UPDATE_CHECK'))
                            OR (r.resource_type = 'REVIEW' AND x.command_type = 'PUBLISH_REVIEW'))
                     ORDER BY x.aggregate_sequence DESC LIMIT 1
              ) latest ON true
              LEFT JOIN LATERAL (
                    SELECT x.payload_hash
                      FROM outbox_command x
                     WHERE r.resource_type = 'CHECK_RUN'
                       AND x.pr_subject_id = r.pr_subject_id
                       AND x.pr_revision_id = o.pr_revision_id
                       AND x.state = 'CONFIRMED' AND x.command_type = 'CREATE_CHECK'
                     ORDER BY x.aggregate_sequence ASC LIMIT 1
              ) base ON true
            """;

    private final JdbcClient jdbc;

    public PostgresRepairRequestRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public List<RepairCandidate> findReady(int limit) {
        return jdbc.sql(PROJECTION + """
                 WHERE ((rr.state = 'PENDING' AND rr.policy_tier = 'AUTO') OR rr.state = 'APPROVED'
                        OR (rr.state = 'RETRY_WAIT' AND rr.next_attempt_at <= now()))
                 ORDER BY rr.created_at LIMIT :limit
                """).param("limit", limit).query(this::map).list();
    }

    @Override
    public Optional<RepairCandidate> lockReady(UUID requestId) {
        return jdbc.sql(PROJECTION + """
                 WHERE rr.id = :id
                   AND ((rr.state = 'PENDING' AND rr.policy_tier = 'AUTO') OR rr.state = 'APPROVED'
                        OR (rr.state = 'RETRY_WAIT' AND rr.next_attempt_at <= now()))
                 FOR UPDATE OF rr SKIP LOCKED
                """).param("id", requestId).query(this::map).optional();
    }

    @Override
    public boolean markDispatched(UUID id, UUID runId, UUID operationId) {
        return jdbc.sql("""
                UPDATE repair_request SET state='DISPATCHED', repair_run_id=:runId,
                    repair_operation_id=:opId, attempt_count=attempt_count+1,
                    next_attempt_at=NULL, last_error=NULL, updated_at=now()
                 WHERE id=:id AND state IN ('PENDING','APPROVED','RETRY_WAIT')
                """).param("id", id).param("runId", runId).param("opId", operationId).update() == 1;
    }

    @Override public boolean markExpired(UUID id, String reason) {
        return terminal(id, "EXPIRED", reason);
    }
    @Override public boolean markFailedTerminal(UUID id, String error) {
        return terminal(id, "FAILED_TERMINAL", error);
    }
    private boolean terminal(UUID id, String state, String error) {
        return jdbc.sql("UPDATE repair_request SET state=:state,last_error=:error,updated_at=now() "
                        + "WHERE id=:id AND state IN ('PENDING','APPROVED','RETRY_WAIT','DISPATCHED')")
                .param("state", state).param("error", error).param("id", id).update() == 1;
    }

    @Override public boolean markRetryWait(UUID id, Duration backoff, String error) {
        return jdbc.sql("""
                UPDATE repair_request SET state=CASE WHEN attempt_count + 1 >= max_attempts
                    THEN 'FAILED_TERMINAL' ELSE 'RETRY_WAIT' END,
                    attempt_count=attempt_count+1,
                    next_attempt_at=CASE WHEN attempt_count + 1 >= max_attempts THEN NULL
                        ELSE now()+make_interval(secs => :secs) END,
                    last_error=:error, updated_at=now()
                 WHERE id=:id AND state IN ('PENDING','APPROVED','RETRY_WAIT')
                """).param("id", id).param("secs", backoff.toSeconds())
                .param("error", error).update() == 1;
    }

    @Override
    public List<RepairRunOutcome> findTerminalRunOutcomes(int limit) {
        return jdbc.sql("""
                SELECT rr.id request_id, rr.repair_run_id, r.pr_revision_id, rr.state
                  FROM repair_request rr
                  JOIN review_run r ON r.id=rr.repair_run_id
                 WHERE rr.state IN ('REPAIRED','FAILED_TERMINAL','EXPIRED')
                   AND r.run_mode='REPAIR' AND r.state='CREATED'
                 ORDER BY rr.updated_at LIMIT :limit
                """).param("limit", limit).query(this::mapRunOutcome).list();
    }

    @Override
    public Optional<RepairRunOutcome> lockTerminalRunOutcome(UUID requestId) {
        return jdbc.sql("""
                SELECT rr.id request_id, rr.repair_run_id, r.pr_revision_id, rr.state
                  FROM repair_request rr
                  JOIN review_run r ON r.id=rr.repair_run_id
                 WHERE rr.id=:id
                   AND rr.state IN ('REPAIRED','FAILED_TERMINAL','EXPIRED')
                   AND r.run_mode='REPAIR' AND r.state='CREATED'
                 FOR UPDATE OF rr,r
                """).param("id", requestId).query(this::mapRunOutcome).optional();
    }

    private RepairRunOutcome mapRunOutcome(ResultSet rs, int row) throws SQLException {
        return new RepairRunOutcome(rs.getObject("request_id", UUID.class),
                rs.getObject("repair_run_id", UUID.class),
                rs.getObject("pr_revision_id", UUID.class),
                RepairRequestState.valueOf(rs.getString("state")));
    }

    private RepairCandidate map(ResultSet rs, int row) throws SQLException {
        return new RepairCandidate(rs.getObject("request_id", UUID.class),
                RepairPolicyTier.valueOf(rs.getString("policy_tier")),
                RepairRequestState.valueOf(rs.getString("repair_state")),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getObject("resource_id", UUID.class),
                PublicationResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("pr_subject_id", UUID.class),
                rs.getObject("pr_revision_id", UUID.class),
                rs.getObject("current_revision_id", UUID.class),
                rs.getObject("original_run_id", UUID.class),
                rs.getObject("original_root_run_id", UUID.class),
                rs.getObject("latest_operation_id", UUID.class),
                CommandType.valueOf(rs.getString("latest_command_type")),
                rs.getString("aggregate_key"), rs.getString("policy_version"),
                new Digest(rs.getString("latest_payload_hash").trim()),
                new Digest(rs.getString("base_payload_hash").trim()));
    }
}
