package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.NotifySilenceStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * notify_silence 的 Postgres 实现（V125）。时间参数一律显式 Timestamp
 * （JdbcClient 对 Instant 推断不出 SQL 类型——PSQLException 教训，同反馈控制器）。
 */
public class PostgresNotifySilenceStore implements NotifySilenceStore {

    private final JdbcClient jdbc;

    public PostgresNotifySilenceStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<SilenceRow> findActive(Instant now) {
        return jdbc.sql("""
                select id, alertname, service, reason, created_by, created_at,
                       expires_at, state
                  from notify_silence
                 where state = 'ACTIVE' and expires_at > :now
                 order by created_at desc
                """)
                .param("now", Timestamp.from(now))
                .query((rs, i) -> new SilenceRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("alertname"),
                        rs.getString("service"),
                        rs.getString("reason"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("state")))
                .list();
    }

    @Override
    public void insert(SilenceRow row) {
        jdbc.sql("""
                insert into notify_silence (id, alertname, service, reason, created_by,
                    created_at, expires_at, state)
                values (:id, :alertname, :service, :reason, :createdBy, :createdAt,
                    :expiresAt, :state)
                """)
                .param("id", row.id())
                .param("alertname", emptyToNull(row.alertname()))
                .param("service", emptyToNull(row.service()))
                .param("reason", row.reason())
                .param("createdBy", row.createdBy())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .param("expiresAt", Timestamp.from(row.expiresAt()))
                .param("state", row.state())
                .update();
    }

    @Override
    public boolean disable(UUID id, Instant at) {
        int updated = jdbc.sql("""
                update notify_silence set state = 'DISABLED'
                 where id = :id and state = 'ACTIVE'
                """)
                .param("id", id)
                .update();
        return updated > 0;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
