package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.sandbox.ToolCall;
import com.objwww.pr.control.domain.sandbox.ToolCallRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * ToolCall JDBC 仓储实现（M4 §4.1 tool_call 表）。
 *
 * <p>列级 UPDATE（只改状态/观测列，身份列不可改由触发器保证）。
 * Lease epoch fencing：update 只修改 lease_epoch 匹配的行（CAS 语义）。
 */
public class JdbcToolCallRepository implements ToolCallRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcToolCallRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ToolCall toolCall) {
        String sql = """
            INSERT INTO tool_call (
                id, review_run_id, run_step_id, attempt_id, call_seq,
                tool_name, tool_args, state, lease_epoch, started_at,
                exit_code, observation_digest, observation_summary,
                observation_bytes, truncated, finished_at
            ) VALUES (
                :id, :review_run_id, :run_step_id, :attempt_id, :call_seq,
                :tool_name, CAST(:tool_args AS jsonb), :state, :lease_epoch, :started_at,
                :exit_code, :observation_digest, :observation_summary,
                :observation_bytes, :truncated, :finished_at
            )
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", toolCall.id())
            .addValue("review_run_id", toolCall.reviewRunId())
            .addValue("run_step_id", toolCall.runStepId())
            .addValue("attempt_id", toolCall.attemptId())
            .addValue("call_seq", toolCall.callSeq())
            .addValue("tool_name", toolCall.toolName())
            .addValue("tool_args", toolCall.toolArgsJson())
            .addValue("state", toolCall.state().name())
            .addValue("lease_epoch", toolCall.leaseEpoch())
            .addValue("started_at", Timestamp.from(toolCall.startedAt()))
            .addValue("exit_code", toolCall.exitCode())
            .addValue("observation_digest", toolCall.observationDigest() != null ? toolCall.observationDigest().hex() : null)
            .addValue("observation_summary", toolCall.observationSummary())
            .addValue("observation_bytes", toolCall.observationBytes())
            .addValue("truncated", toolCall.truncated())
            .addValue("finished_at", toolCall.finishedAt() != null ? Timestamp.from(toolCall.finishedAt()) : null);

        jdbc.update(sql, params);
    }

    @Override
    public Optional<ToolCall> findById(UUID toolCallId) {
        String sql = """
            SELECT id, review_run_id, run_step_id, attempt_id, call_seq,
                   tool_name, tool_args, state, lease_epoch, started_at,
                   exit_code, observation_digest, observation_summary,
                   observation_bytes, truncated, finished_at
            FROM tool_call
            WHERE id = :id
            """;

        MapSqlParameterSource params = new MapSqlParameterSource("id", toolCallId);
        return jdbc.query(sql, params, TOOL_CALL_MAPPER).stream().findFirst();
    }

    @Override
    public boolean update(ToolCall toolCall, long expectedEpoch) {
        // 列级 UPDATE：只改状态/观测列，身份列不可改（触发器双保险）
        String sql = """
            UPDATE tool_call
            SET state = :state,
                exit_code = :exit_code,
                observation_digest = :observation_digest,
                observation_summary = :observation_summary,
                observation_bytes = :observation_bytes,
                truncated = :truncated,
                finished_at = :finished_at
            WHERE id = :id AND lease_epoch = :expected_epoch
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", toolCall.id())
            .addValue("state", toolCall.state().name())
            .addValue("exit_code", toolCall.exitCode())
            .addValue("observation_digest", toolCall.observationDigest() != null ? toolCall.observationDigest().hex() : null)
            .addValue("observation_summary", toolCall.observationSummary())
            .addValue("observation_bytes", toolCall.observationBytes())
            .addValue("truncated", toolCall.truncated())
            .addValue("finished_at", toolCall.finishedAt() != null ? Timestamp.from(toolCall.finishedAt()) : null)
            .addValue("expected_epoch", expectedEpoch);

        int updated = jdbc.update(sql, params);
        return updated > 0;
    }

    private static final RowMapper<ToolCall> TOOL_CALL_MAPPER = (rs, rowNum) -> new ToolCall(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("review_run_id")),
        UUID.fromString(rs.getString("run_step_id")),
        UUID.fromString(rs.getString("attempt_id")),
        rs.getInt("call_seq"),
        rs.getString("tool_name"),
        rs.getString("tool_args"),
        rs.getLong("lease_epoch"),
        rs.getTimestamp("started_at").toInstant(),
        ToolCall.ToolCallState.valueOf(rs.getString("state")),
        (Integer) rs.getObject("exit_code"),
        rs.getString("observation_digest") != null ? new Digest(rs.getString("observation_digest")) : null,
        rs.getString("observation_summary"),
        (Long) rs.getObject("observation_bytes"),
        rs.getBoolean("truncated"),
        rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null
    );
}
