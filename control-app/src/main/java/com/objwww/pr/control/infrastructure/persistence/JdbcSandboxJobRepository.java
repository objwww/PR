package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.sandbox.SandboxJob;
import com.objwww.pr.control.domain.sandbox.SandboxJobRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SandboxJob JDBC 仓储实现（M4 §4.1 sandbox_job 表，§4.2 生命周期服务）。
 *
 * <p>核心机制：
 * <ul>
 *   <li>列级 UPDATE（只改状态/租约/结果列，JobSpec 身份列不可改由触发器保证）</li>
 *   <li>Lease epoch fencing：update/renewLease 只修改 lease_epoch 匹配的行（CAS 语义）</li>
 *   <li>全局并发闸：claimNext 使用 SKIP LOCKED + uq_sandbox_job_inflight 部分唯一索引</li>
 * </ul>
 */
public class JdbcSandboxJobRepository implements SandboxJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSandboxJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SandboxJob job) {
        String sql = """
            INSERT INTO sandbox_job (
                id, tool_call_id, review_run_id, run_step_id, attempt_id,
                worker_id, state, lease_owner, lease_until, lease_epoch,
                heartbeat_at, attempt_count, max_attempts, container_id, exit_code,
                result_digest, log_digest, error_code, sanitized_message,
                failure_class, retryable, created_at, started_at, finished_at,
                job_spec_immutable
            ) VALUES (
                :id, :tool_call_id, :review_run_id, :run_step_id, :attempt_id,
                :worker_id, :state, :lease_owner, :lease_until, :lease_epoch,
                :heartbeat_at, :attempt_count, :max_attempts, :container_id, :exit_code,
                :result_digest, :log_digest, :error_code, :sanitized_message,
                :failure_class, :retryable, :created_at, :started_at, :finished_at,
                CAST(:job_spec_immutable AS jsonb)
            )
            """;

        MapSqlParameterSource params = buildParams(job);
        jdbc.update(sql, params);
    }

    @Override
    public Optional<SandboxJob> findById(UUID jobId) {
        String sql = """
            SELECT id, tool_call_id, review_run_id, run_step_id, attempt_id,
                   worker_id, state, lease_owner, lease_until, lease_epoch,
                   heartbeat_at, attempt_count, max_attempts, container_id, exit_code,
                   result_digest, log_digest, error_code, sanitized_message,
                   failure_class, retryable, created_at, started_at, finished_at,
                   job_spec_immutable
            FROM sandbox_job
            WHERE id = :id
            """;

        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId);
        return jdbc.query(sql, params, SANDBOX_JOB_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<SandboxJob> findByToolCallId(UUID toolCallId) {
        String sql = """
            SELECT id, tool_call_id, review_run_id, run_step_id, attempt_id,
                   worker_id, state, lease_owner, lease_until, lease_epoch,
                   heartbeat_at, attempt_count, max_attempts, container_id, exit_code,
                   result_digest, log_digest, error_code, sanitized_message,
                   failure_class, retryable, created_at, started_at, finished_at,
                   job_spec_immutable
            FROM sandbox_job
            WHERE tool_call_id = :tool_call_id
            """;

        MapSqlParameterSource params = new MapSqlParameterSource("tool_call_id", toolCallId);
        return jdbc.query(sql, params, SANDBOX_JOB_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<SandboxJob> claimNext(String leaseOwner, int leaseDurationSeconds, String workerId) {
        // SKIP LOCKED：防止两个 claimer 领同一行
        // uq_sandbox_job_inflight：全局并发闸，保证同时 LEASED ≤ 1
        String sql = """
            UPDATE sandbox_job
            SET state = 'LEASED',
                lease_owner = :lease_owner,
                lease_until = now() + make_interval(secs => :lease_duration),
                lease_epoch = lease_epoch + 1,
                worker_id = :worker_id,
                attempt_count = attempt_count + 1,
                started_at = now()
            WHERE id = (
                SELECT id FROM sandbox_job
                WHERE state = 'PENDING'
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING id, tool_call_id, review_run_id, run_step_id, attempt_id,
                      worker_id, state, lease_owner, lease_until, lease_epoch,
                      heartbeat_at, attempt_count, max_attempts, container_id, exit_code,
                      result_digest, log_digest, error_code, sanitized_message,
                      failure_class, retryable, created_at, started_at, finished_at,
                      job_spec_immutable
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("lease_owner", leaseOwner)
            .addValue("lease_duration", leaseDurationSeconds)
            .addValue("worker_id", workerId);

        return jdbc.query(sql, params, SANDBOX_JOB_MAPPER).stream().findFirst();
    }

    @Override
    public boolean update(SandboxJob job, long expectedEpoch) {
        // 列级 UPDATE：只改状态/租约/结果列，JobSpec 身份列不可改（触发器双保险）
        String sql = """
            UPDATE sandbox_job
            SET state = :state,
                lease_owner = :lease_owner,
                lease_until = :lease_until,
                lease_epoch = :lease_epoch,
                heartbeat_at = :heartbeat_at,
                attempt_count = :attempt_count,
                container_id = :container_id,
                exit_code = :exit_code,
                result_digest = :result_digest,
                log_digest = :log_digest,
                error_code = :error_code,
                sanitized_message = :sanitized_message,
                failure_class = :failure_class,
                retryable = :retryable,
                started_at = :started_at,
                finished_at = :finished_at,
                worker_id = :worker_id
            WHERE id = :id AND lease_epoch = :expected_epoch
            """;

        MapSqlParameterSource params = buildParams(job)
            .addValue("expected_epoch", expectedEpoch);

        int updated = jdbc.update(sql, params);
        return updated > 0;
    }

    @Override
    public boolean renewLease(UUID jobId, long expectedEpoch, int leaseDurationSeconds) {
        String sql = """
            UPDATE sandbox_job
            SET lease_until = now() + interval ':lease_duration seconds',
                heartbeat_at = now()
            WHERE id = :id AND lease_epoch = :expected_epoch AND state = 'LEASED'
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", jobId)
            .addValue("expected_epoch", expectedEpoch)
            .addValue("lease_duration", leaseDurationSeconds);

        int updated = jdbc.update(sql, params);
        return updated > 0;
    }

    private MapSqlParameterSource buildParams(SandboxJob job) {
        return new MapSqlParameterSource()
            .addValue("id", job.id())
            .addValue("tool_call_id", job.toolCallId())
            .addValue("review_run_id", job.reviewRunId())
            .addValue("run_step_id", job.runStepId())
            .addValue("attempt_id", job.attemptId())
            .addValue("worker_id", job.workerId())
            .addValue("state", job.state().name())
            .addValue("lease_owner", job.leaseOwner())
            .addValue("lease_until", job.leaseUntil() != null ? Timestamp.from(job.leaseUntil()) : null)
            .addValue("lease_epoch", job.leaseEpoch())
            .addValue("heartbeat_at", job.heartbeatAt() != null ? Timestamp.from(job.heartbeatAt()) : null)
            .addValue("attempt_count", job.attemptCount())
            .addValue("max_attempts", job.maxAttempts())
            .addValue("container_id", job.containerId())
            .addValue("exit_code", job.exitCode())
            .addValue("result_digest", job.resultDigest() != null ? job.resultDigest().hex() : null)
            .addValue("log_digest", job.logDigest() != null ? job.logDigest().hex() : null)
            .addValue("error_code", job.errorCode())
            .addValue("sanitized_message", job.sanitizedMessage())
            .addValue("failure_class", job.failureClass() != null ? job.failureClass().name() : null)
            .addValue("retryable", job.retryable())
            .addValue("created_at", Timestamp.from(job.createdAt()))
            .addValue("started_at", job.startedAt() != null ? Timestamp.from(job.startedAt()) : null)
            .addValue("finished_at", job.finishedAt() != null ? Timestamp.from(job.finishedAt()) : null)
            .addValue("job_spec_immutable", job.jobSpecImmutable());
    }

    private static final RowMapper<SandboxJob> SANDBOX_JOB_MAPPER = (rs, rowNum) -> {
        String failureClassStr = rs.getString("failure_class");
        FailureClass failureClass = failureClassStr != null ? FailureClass.valueOf(failureClassStr) : null;

        Timestamp leaseUntilTs = rs.getTimestamp("lease_until");
        Instant leaseUntil = leaseUntilTs != null ? leaseUntilTs.toInstant() : null;

        Timestamp heartbeatAtTs = rs.getTimestamp("heartbeat_at");
        Instant heartbeatAt = heartbeatAtTs != null ? heartbeatAtTs.toInstant() : null;

        Timestamp startedAtTs = rs.getTimestamp("started_at");
        Instant startedAt = startedAtTs != null ? startedAtTs.toInstant() : null;

        Timestamp finishedAtTs = rs.getTimestamp("finished_at");
        Instant finishedAt = finishedAtTs != null ? finishedAtTs.toInstant() : null;

        String resultDigestStr = rs.getString("result_digest");
        Digest resultDigest = resultDigestStr != null ? new Digest(resultDigestStr) : null;

        String logDigestStr = rs.getString("log_digest");
        Digest logDigest = logDigestStr != null ? new Digest(logDigestStr) : null;

        Boolean retryable = (Boolean) rs.getObject("retryable");

        return new SandboxJob(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tool_call_id")),
            UUID.fromString(rs.getString("review_run_id")),
            UUID.fromString(rs.getString("run_step_id")),
            UUID.fromString(rs.getString("attempt_id")),
            rs.getString("job_spec_immutable"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("worker_id"),
            SandboxJob.JobState.valueOf(rs.getString("state")),
            rs.getString("lease_owner"),
            leaseUntil,
            rs.getLong("lease_epoch"),
            heartbeatAt,
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            rs.getString("container_id"),
            (Integer) rs.getObject("exit_code"),
            resultDigest,
            logDigest,
            rs.getString("error_code"),
            rs.getString("sanitized_message"),
            failureClass,
            retryable,
            startedAt,
            finishedAt
        );
    };
}
