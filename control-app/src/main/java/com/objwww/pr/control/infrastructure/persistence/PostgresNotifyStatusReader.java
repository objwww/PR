package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.dutybot.domain.NotifyStatusReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V9 notify_outbox 只读状态面（UX-02 机器人"通知查询"意图；control_app 本持
 * SELECT，零新授权、零写面）。失败面 = DEAD/RETRY_WAIT（终态失败 + 退避重试中；
 * PENDING/CLAIMED 是正常在途，不算异常——如实口径）。
 */
public class PostgresNotifyStatusReader implements NotifyStatusReader {

    private final JdbcClient jdbc;

    public PostgresNotifyStatusReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OutboxStatus summarize(int problemLimit) {
        Map<String, Long> byState = new LinkedHashMap<>();
        jdbc.sql("select state, count(*) as n from notify_outbox group by state order by state")
                .query((rs, i) -> {
                    byState.put(rs.getString("state"), rs.getLong("n"));
                    return null;
                }).list();
        long total = byState.values().stream().mapToLong(Long::longValue).sum();

        List<ProblemRow> problems = jdbc.sql("""
                select id, report_id, channel, state, attempt_count, last_error, created_at
                from notify_outbox
                where state in ('DEAD', 'RETRY_WAIT')
                order by created_at desc, id desc limit :limit
                """)
                .param("limit", problemLimit)
                .query((rs, i) -> new ProblemRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("report_id", UUID.class),
                        rs.getString("channel"), rs.getString("state"),
                        rs.getInt("attempt_count"), rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        return new OutboxStatus(total, byState, problems);
    }
}
