package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_tool_call 的 Postgres 实现（V9；M3-07）。
 *
 * <p>栅栏直挂（FUT-50）：整批以 INSERT...SELECT WHERE EXISTS（run.generation 一致）
 * 写入，栅栏不匹配 = 0 行整批拒绝，不落半批；重复 (result, tool_call_id) 走 PK 去重
 * （ON CONFLICT DO NOTHING——tool_calls 是元数据，重放幂等）。
 */
public class PostgresRcaToolCallRepository implements RcaToolCallRepository {

    private static final String INSERT_SQL = """
            INSERT INTO rca_tool_call (
                investigation_result_id, tool_call_id, sequence_no, tool_name, status,
                params_digest, result_digest, started_at, finished_at,
                run_id, observed_generation, schema_version, payload_digest
            )
            SELECT :resultId, :toolCallId, :sequenceNo, :toolName, :status,
                   :paramsDigest, :resultDigest, :startedAt, :finishedAt,
                   :runId, :observedGeneration, :schemaVersion, :payloadDigest
            WHERE EXISTS (SELECT 1 FROM rca_run r
                          WHERE r.id = :runId AND r.generation = :observedGeneration)
            ON CONFLICT (investigation_result_id, tool_call_id) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public PostgresRcaToolCallRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public int insertAll(List<RcaToolCall> toolCalls) {
        int written = 0;
        for (RcaToolCall tc : toolCalls) {
            written += jdbc.sql(INSERT_SQL)
                    .param("resultId", tc.investigationResultId())
                    .param("toolCallId", tc.toolCallId())
                    .param("sequenceNo", tc.sequenceNo())
                    .param("toolName", tc.toolName())
                    .param("status", tc.status() == null ? null : tc.status().name())
                    .param("paramsDigest", hash(tc.paramsDigest()))
                    .param("resultDigest", hash(tc.resultDigest()))
                    .param("startedAt", ts(tc.startedAt()))
                    .param("finishedAt", ts(tc.finishedAt()))
                    .param("runId", tc.runId())
                    .param("observedGeneration", tc.observedGeneration())
                    .param("schemaVersion", tc.schemaVersion())
                    .param("payloadDigest", hash(tc.payloadDigest()))
                    .update();
        }
        return written;
    }

    @Override
    public List<RcaToolCall> findByResultId(UUID investigationResultId) {
        return jdbc.sql("""
                        SELECT * FROM rca_tool_call WHERE investigation_result_id = :rid
                        ORDER BY sequence_no
                        """)
                .param("rid", investigationResultId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public List<RcaToolCall> findByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM rca_tool_call WHERE run_id = :runId ORDER BY created_at")
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private RcaToolCall mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String status = rs.getString("status");
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new RcaToolCall(
                rs.getObject("investigation_result_id", UUID.class),
                rs.getString("tool_call_id"),
                rs.getInt("sequence_no"),
                rs.getString("tool_name"),
                status == null ? null : ToolCallStatus.valueOf(status),
                digest(rs.getString("params_digest")),
                digest(rs.getString("result_digest")),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                rs.getObject("run_id", UUID.class),
                rs.getInt("observed_generation"),
                rs.getInt("schema_version"),
                digest(rs.getString("payload_digest")));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String hash(Digest digest) {
        return digest == null ? null : digest.value();
    }

    private static Digest digest(String value) {
        return value == null ? null : new Digest(value);
    }
}
