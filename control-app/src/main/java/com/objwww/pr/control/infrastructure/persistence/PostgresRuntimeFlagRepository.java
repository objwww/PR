package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RuntimeFlagRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * alert_runtime_flag 的 Postgres 实现（JE-01，V159）。单行旗标 upsert 幂等；
 * 读面同事务一致性由单语句保证（每方法短事务，端口契约同律）。
 */
public class PostgresRuntimeFlagRepository implements RuntimeFlagRepository {

    private final JdbcClient jdbc;

    public PostgresRuntimeFlagRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Boolean> findEnabled(String name) {
        List<Boolean> rows = jdbc.sql(
                        "SELECT enabled FROM alert_runtime_flag WHERE name = :name")
                .param("name", name)
                .query(Boolean.class)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsert(String name, boolean enabled, String updatedBy, String reason,
            Instant at) {
        jdbc.sql("""
                INSERT INTO alert_runtime_flag
                    (name, enabled, reason, updated_by, updated_at)
                VALUES (:name, :enabled, :reason, :updatedBy, :updatedAt)
                ON CONFLICT (name) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    reason = EXCLUDED.reason,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("name", name)
                .param("enabled", enabled)
                .param("reason", reason)
                .param("updatedBy", updatedBy)
                .param("updatedAt", Timestamp.from(at))
                .update();
    }

    @Override
    public Optional<FlagRow> findByName(String name) {
        return jdbc.sql("""
                        SELECT name, enabled, updated_by, reason, updated_at
                          FROM alert_runtime_flag WHERE name = :name
                        """)
                .param("name", name)
                .query((rs, n) -> new FlagRow(rs.getString("name"),
                        rs.getBoolean("enabled"), rs.getString("updated_by"),
                        rs.getString("reason"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }
}
