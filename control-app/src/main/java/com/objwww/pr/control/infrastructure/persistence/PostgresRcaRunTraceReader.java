package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RcaRunTraceReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RcaRunTraceReader} 的 Postgres 实现（3.17 Trace 瀑布页签）。
 * 三张账本 UNION ALL 单查询直出统一 span 投影；usage 与 V48 聚合读面同键
 * （usage jsonb {prompt_tokens, completion_tokens, total_tokens}，裸列不存在——
 * 195 真机 R4 探针教训：裸列名 SELECT 真 PG 直接 BadSqlGrammar）。
 */
public class PostgresRcaRunTraceReader implements RcaRunTraceReader {

    private static final String SQL = """
            SELECT s.kind, s.id, s.task_id, s.label, s.seq, s.state, s.started_at, s.finished_at,
                   s.latency_ms, s.model, s.prompt_tokens, s.completion_tokens, s.total_tokens,
                   s.cost_micros, s.error_code, s.worker_id, s.attempt_no, s.role_id
              FROM (
                SELECT 'task' AS kind, a.id, a.task_id, t.task_key AS label,
                       a.attempt_no::bigint AS seq, a.status AS state,
                       a.started_at AS started_at, a.finished_at AS finished_at,
                       NULL::bigint AS latency_ms, NULL AS model,
                       NULL::integer AS prompt_tokens, NULL::integer AS completion_tokens,
                       NULL::integer AS total_tokens, NULL::bigint AS cost_micros,
                       a.error_code, a.worker_id, a.attempt_no, NULL AS role_id
                  FROM rca_attempt a
                  JOIN rca_task t ON t.id = a.task_id
                 WHERE t.run_id = :runId
                UNION ALL
                SELECT 'model', mc.id, mc.task_id, mc.role_id, mc.action_seq, mc.state,
                       mc.created_at, NULL::timestamptz,
                       mc.latency_ms, mc.requested_model,
                       (mc.usage->>'prompt_tokens')::integer,
                       (mc.usage->>'completion_tokens')::integer,
                       (mc.usage->>'total_tokens')::integer,
                       mc.cost_micros, mc.error_code, NULL, NULL, mc.role_id
                  FROM rca_model_call mc
                 WHERE mc.run_id = :runId
                UNION ALL
                SELECT 'tool', ti.id, ti.task_id, ti.tool_name, ti.call_seq, ti.state,
                       ti.started_at, ti.settled_at,
                       NULL::bigint, NULL,
                       NULL::integer, NULL::integer, NULL::integer,
                       NULL::bigint, ti.reason_code, NULL, NULL, NULL
                  FROM rca_tool_invocation ti
                 WHERE ti.run_id = :runId
              ) s
             ORDER BY s.started_at ASC NULLS LAST, s.kind ASC, s.seq ASC
            """;

    private final JdbcClient jdbc;

    public PostgresRcaRunTraceReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<SpanRow> spansByRun(UUID runId) {
        return jdbc.sql(SQL)
                .param("runId", runId)
                .query((rs, rowNum) -> new SpanRow(
                        rs.getString("kind"),
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("task_id")),
                        rs.getString("label"),
                        rs.getLong("seq"),
                        rs.getString("state"),
                        toIso(rs.getTimestamp("started_at")),
                        toIso(rs.getTimestamp("finished_at")),
                        rs.getObject("latency_ms", Long.class),
                        rs.getString("model"),
                        rs.getObject("prompt_tokens", Integer.class),
                        rs.getObject("completion_tokens", Integer.class),
                        rs.getObject("total_tokens", Integer.class),
                        rs.getObject("cost_micros", Long.class),
                        rs.getString("error_code"),
                        rs.getString("worker_id"),
                        rs.getObject("attempt_no", Integer.class),
                        rs.getString("role_id")))
                .list();
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
