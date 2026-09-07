package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RcaEventReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * V14 rca_event 只读面（M5-13）。append-only 表 + rca_run.last_event_seq 计数器
 * 是真相源（表+游标，零迁移）：latestSeq 读计数器而非 max(seq)——事件行未来若
 * 归档/清理，计数器仍是缺口判定的正确锚（drained-gap 检测依赖它）。
 */
public class PostgresRcaEventReader implements RcaEventReader {

    private final JdbcClient jdbc;

    public PostgresRcaEventReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<EventRow> readAfter(UUID runId, long afterSeq, int limit) {
        return jdbc.sql("""
                        SELECT seq, event_type, CAST(payload AS text) AS payload, created_at
                          FROM rca_event
                         WHERE run_id = :run AND seq > :after
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("run", runId)
                .param("after", afterSeq)
                .param("limit", limit)
                .query((rs, n) -> new EventRow(
                        rs.getLong("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public OptionalLong latestSeq(UUID runId) {
        return jdbc.sql("SELECT last_event_seq FROM rca_run WHERE id = :id")
                .param("id", runId)
                .query((rs, n) -> rs.getLong("last_event_seq"))
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }
}
