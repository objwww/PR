package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.RcaModelOutputReadPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 输出捕获读面的 Postgres 实现（V167）：账行（rca_model_call）左连输入
 * （V90）/输出（V167）两捕获表，按 action_seq 升序一次取齐——无捕获行侧
 * null 如实（输入捕获读面 PostgresRcaModelInputReplay 的 run 级对称版）。
 */
public class PostgresRcaModelOutputRead implements RcaModelOutputReadPort {

    private final JdbcClient jdbc;

    public PostgresRcaModelOutputRead(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<StepRow> byRun(UUID runId, int limit) {
        return jdbc.sql("""
                select c.id as model_call_id, c.action_seq, c.role_id, c.state,
                       i.capture_level as in_level, i.prompt_text as in_text,
                       i.prompt_digest as in_digest, i.message_bytes as in_bytes,
                       i.redaction_note as in_note,
                       o.capture_level as out_level, o.output_text as out_text,
                       o.output_digest as out_digest, o.message_bytes as out_bytes,
                       o.redaction_note as out_note
                from rca_model_call c
                left join rca_model_input i on i.model_call_id = c.id
                left join rca_model_output o on o.model_call_id = c.id
                where c.run_id = :runId
                order by c.action_seq, c.created_at
                limit :limit
                """)
                .param("runId", runId)
                .param("limit", limit)
                .query((rs, rowNum) -> new StepRow(
                        rs.getObject("model_call_id", UUID.class),
                        rs.getLong("action_seq"),
                        rs.getString("role_id"),
                        rs.getString("state"),
                        side(rs.getString("in_level"), rs.getString("in_text"),
                                rs.getString("in_digest"),
                                (Integer) rs.getObject("in_bytes"),
                                rs.getString("in_note")),
                        side(rs.getString("out_level"), rs.getString("out_text"),
                                rs.getString("out_digest"),
                                (Integer) rs.getObject("out_bytes"),
                                rs.getString("out_note"))))
                .list();
    }

    /** 无捕获行（左连全 null）→ 该侧 null 如实；有行则按档投影（text 可空=DIGEST_ONLY） */
    private static CaptureSide side(String level, String text, String digest,
            Integer bytes, String note) {
        if (level == null) {
            return null;
        }
        return new CaptureSide(level, text, digest, bytes, note);
    }
}
