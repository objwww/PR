package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.ExternalInvocationState;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * external_invocation_ledger 的 Postgres 实现（V5 账本同形态：STARTED 短事务 + 终态列级 UPDATE）。
 */
public class PostgresExternalInvocationRepository implements ExternalInvocationRepository {

    private static final String INSERT_STARTED_SQL = """
            INSERT INTO external_invocation_ledger (
                id, invocation_id, call_seq, run_id, task_id, attempt_id, lease_epoch,
                endpoint, request_digest, state, started_at
            ) VALUES (
                :id, :invocationId, :callSeq, :runId, :taskId, :attemptId, :leaseEpoch,
                :endpoint, :requestDigest, 'STARTED', :startedAt
            )
            """;

    private static final String FINISH_SQL = """
            UPDATE external_invocation_ledger SET
                state = :state, response_digest = :responseDigest, http_status = :httpStatus,
                latency_ms = :latencyMs,
                prompt_tokens = :promptTokens, completion_tokens = :completionTokens,
                total_tokens = :totalTokens, usage_missing = :usageMissing,
                holmes_version = :holmesVersion, model = :model, toolset_version = :toolsetVersion,
                error_class = :errorClass, sanitized_message = :sanitizedMessage,
                finished_at = :finishedAt
             WHERE id = :id AND state = 'STARTED'
            """;

    private final JdbcClient jdbc;

    public PostgresExternalInvocationRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertStarted(ExternalInvocation invocation) {
        jdbc.sql(INSERT_STARTED_SQL)
                .param("id", invocation.id())
                .param("invocationId", invocation.invocationId())
                .param("callSeq", invocation.callSeq())
                .param("runId", invocation.runId())
                .param("taskId", invocation.taskId())
                .param("attemptId", invocation.attemptId())
                .param("leaseEpoch", invocation.leaseEpoch())
                .param("endpoint", invocation.endpoint())
                .param("requestDigest", invocation.requestDigest().value())
                // BA-13①:startedAt 走绑定参数(应用时钟单一事实源),不用 DB now()
                .param("startedAt", ts(invocation.startedAt()))
                .update();
    }

    @Override
    public boolean finish(ExternalInvocation invocation) {
        return jdbc.sql(FINISH_SQL)
                .param("state", invocation.state().name())
                .param("responseDigest", hash(invocation.responseDigest()))
                .param("httpStatus", invocation.httpStatus())
                .param("latencyMs", invocation.latencyMs())
                .param("promptTokens", invocation.promptTokens())
                .param("completionTokens", invocation.completionTokens())
                .param("totalTokens", invocation.totalTokens())
                .param("usageMissing", invocation.usageMissing())
                .param("holmesVersion", invocation.holmesVersion())
                .param("model", invocation.model())
                .param("toolsetVersion", invocation.toolsetVersion())
                .param("errorClass", invocation.errorClass())
                .param("sanitizedMessage", invocation.sanitizedMessage())
                .param("finishedAt", ts(invocation.finishedAt()))
                .param("id", invocation.id())
                .update() > 0;
    }

    @Override
    public List<ExternalInvocation> findHangingStarted(Instant olderThan) {
        return jdbc.sql("SELECT * FROM external_invocation_ledger"
                        + " WHERE state = 'STARTED' AND started_at < :threshold")
                .param("threshold", Timestamp.from(olderThan))
                .query(this::mapRow)
                .list();
    }

    @Override
    public List<ExternalInvocation> findByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM external_invocation_ledger"
                        + " WHERE run_id = :runId ORDER BY started_at")
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String hash(Digest digest) {
        return digest == null ? null : digest.value();
    }

    private ExternalInvocation mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String responseDigest = rs.getString("response_digest");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new ExternalInvocation(
                rs.getObject("id", UUID.class),
                rs.getObject("invocation_id", UUID.class),
                rs.getInt("call_seq"),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getLong("lease_epoch"),
                rs.getString("endpoint"),
                new Digest(rs.getString("request_digest")),
                responseDigest == null ? null : new Digest(responseDigest),
                ExternalInvocationState.valueOf(rs.getString("state")),
                (Integer) rs.getObject("http_status"),
                (Long) rs.getObject("latency_ms"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getBoolean("usage_missing"),
                rs.getString("holmes_version"),
                rs.getString("model"),
                rs.getString("toolset_version"),
                rs.getString("error_class"),
                rs.getString("sanitized_message"),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    }
}
