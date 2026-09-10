package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.auth.domain.AuthEvent;
import com.objwww.pr.control.auth.domain.AuthEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;

/** auth_event 的 Postgres 实现（EX-C3a；append-only）。 */
public class PostgresAuthEventRepository implements AuthEventRepository {

    private final JdbcClient jdbc;

    public PostgresAuthEventRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public void record(AuthEvent event) {
        // B-36 律：拼接面用显式空格单行字面量，禁裸文本块拼接（incidentWHERE 教训）
        jdbc.sql("INSERT INTO auth_event (actor, event_type, remote_addr, detail, occurred_at)"
                        + " VALUES (:actor, :eventType, :remoteAddr, :detail, :occurredAt)")
                .param("actor", event.actor())
                .param("eventType", event.eventType().name())
                .param("remoteAddr", event.remoteAddr())
                .param("detail", event.detail())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }
}
