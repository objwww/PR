package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RcaToolSpanDetailReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RcaToolSpanDetailReader} 的 Postgres 实现（M-d T2）。
 *
 * <p>关联口径：rca_tool_invocation.result_ref → rca_evidence.id（V38 外键），LEFT JOIN
 * ——result_ref 为 null（VALIDATE_ONLY 受理/失败调用/复用未落 ref）的行三要素如实为
 * null。截断在 SQL 内完成（left = 字符数），payload/scope 为 canonical TEXT 列
 * （V16 纪律：禁 jsonb——字节无法原样回读）。
 */
public class PostgresRcaToolSpanDetailReader implements RcaToolSpanDetailReader {

    /** 包内可见供 SQL 契约测试锁定形状（截断/关联/排序纪律） */
    static final String SQL = """
            SELECT ti.id, ti.task_id, ti.call_seq,
                   left(e.scope, 300)   AS scope_summary,
                   left(e.payload, 500) AS result_summary,
                   ti.result_ref::text  AS evidence_ref,
                   e.evidence_type      AS evidence_type,
                   e.source             AS evidence_source
              FROM rca_tool_invocation ti
              LEFT JOIN rca_evidence e ON e.id = ti.result_ref
             WHERE ti.run_id = :runId
             ORDER BY ti.started_at ASC, ti.call_seq ASC
            """;

    private final JdbcClient jdbc;

    public PostgresRcaToolSpanDetailReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<ToolSpanDetail> detailsByRun(UUID runId) {
        return jdbc.sql(SQL)
                .param("runId", runId)
                .query((rs, rowNum) -> new ToolSpanDetail(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("task_id")),
                        rs.getLong("call_seq"),
                        rs.getString("scope_summary"),
                        rs.getString("result_summary"),
                        rs.getString("evidence_ref"),
                        rs.getString("evidence_type"),
                        rs.getString("evidence_source")))
                .list();
    }
}
