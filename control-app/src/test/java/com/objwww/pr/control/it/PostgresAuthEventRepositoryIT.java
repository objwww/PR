package com.objwww.pr.control.it;

import com.objwww.pr.control.auth.domain.AuthEvent;
import com.objwww.pr.control.auth.domain.AuthEventType;
import com.objwww.pr.control.infrastructure.persistence.PostgresAuthEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * auth_event 真 PG 组件测试（EX-C3a；V42）：审计行落库可读回（actor/event_type/
 * remote_addr/detail/occurred_at default now()）、event_type check 拒非法值、
 * V42 授权面（control_app 只 select,insert+序列——审计 append-only 免篡改）。
 * 本机无 Docker 自动跳过（真证据由 195 全量 verify 零跳释放）。
 */
class PostgresAuthEventRepositoryIT extends PostgresITBase {

    private PostgresAuthEventRepository repo;

    @BeforeEach
    void setUp() {
        adminJdbc.sql("DELETE FROM auth_event").update();
        repo = new PostgresAuthEventRepository(JdbcClient.create(controlDataSource()));
    }

    @Test
    void recordLandsReadableRowWithDefaults() {
        Instant before = Instant.now();
        repo.record(new AuthEvent("operator", AuthEventType.LOGIN_SUCCESS,
                "203.0.113.7", null, Instant.now()));

        var row = adminJdbc.sql("""
                        SELECT actor, event_type, remote_addr, detail, occurred_at
                        FROM auth_event WHERE actor = 'operator'
                        """)
                .query((rs, i) -> new Object[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5)})
                .single();
        assertThat(row[0]).isEqualTo("operator");
        assertThat(row[1]).isEqualTo("LOGIN_SUCCESS");
        assertThat(row[2]).isEqualTo("203.0.113.7");
        assertThat(row[3]).isNull();
        // occurred_at 走传入值（仓储显式落 occurred_at；default now() 是 SQL 直插面的兜底）
        assertThat(((java.sql.Timestamp) row[4]).toInstant())
                .isAfterOrEqualTo(before.minusSeconds(5));

        repo.record(new AuthEvent("machine:alertmanager", AuthEventType.LOGOUT,
                null, "drill", Instant.now()));
        assertThat(count("auth_event")).isEqualTo(2);
    }

    @Test
    void unknownEventTypeIsRejectedByCheck() {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> adminJdbc.sql("""
                                INSERT INTO auth_event (actor, event_type) VALUES ('ghost', 'KNOCK')
                                """).update())
                .as("event_type check 三枚举门（LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT）");
    }

    @Test
    void v42GrantsAppendOnlyForControlApp() {
        repo.record(new AuthEvent("operator", AuthEventType.LOGIN_FAILURE,
                "10.0.0.2", null, Instant.now()));

        // control_app：insert+select 放行
        var viaControl = controlJdbc.sql("SELECT count(*) FROM auth_event")
                .query(Long.class).single();
        assertThat(viaControl).isEqualTo(1L);

        // append-only：update/delete 授权面为零（审计免篡改）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql(
                                "UPDATE auth_event SET actor = 'tampered'").update());
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql("DELETE FROM auth_event").update());

        // eval_app 全零
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql("SELECT count(*) FROM auth_event")
                        .query(Long.class).single());
    }
}
