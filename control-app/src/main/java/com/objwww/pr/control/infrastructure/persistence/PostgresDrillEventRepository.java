package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link DrillEventRepository} 的 Postgres 实现（DR-02，V86 drill_event，
 * insert-only 账本）：seq 由库 identity 生成（insert 走 DEFAULT），读面按 seq 升序
 * （单调序 = 事件游标锚）。control_app 与 eval_app 同授权（select,insert），
 * 零 update/delete 开口。
 */
public class PostgresDrillEventRepository implements DrillEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO drill_event (
                id, drill_id, event_type, from_state, to_state, actor, payload, created_at
            ) VALUES (
                :id, :drillId, :type, :fromState, :toState, :actor,
                CAST(:payload AS jsonb), :createdAt
            )
            """;

    private final JdbcClient jdbc;

    public PostgresDrillEventRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(DrillEvent event) {
        jdbc.sql(INSERT_SQL)
                .param("id", event.id())
                .param("drillId", event.drillId())
                .param("type", event.eventType().name())
                .param("fromState", event.fromState())
                .param("toState", event.toState())
                .param("actor", event.actor())
                .param("payload", event.payloadJson())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }

    @Override
    public List<DrillEvent> listByDrill(UUID drillId) {
        return jdbc.sql("""
                        SELECT id, drill_id, seq, event_type, from_state, to_state,
                            actor, payload::text as payload, created_at
                        FROM drill_event WHERE drill_id = :drillId ORDER BY seq
                        """)
                .param("drillId", drillId)
                .query(this::map).list();
    }

    private DrillEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new DrillEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("drill_id", UUID.class),
                rs.getLong("seq"),
                DrillEvent.EventType.valueOf(rs.getString("event_type")),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("actor"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }
}
