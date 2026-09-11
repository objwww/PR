package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V63 rca_run_config_epoch 的 Postgres 实现（EN-04）。append = INSERT ... ON
 * CONFLICT DO NOTHING（pk(run_id, config_epoch) 即 §207 唯一键守卫：并发双切换
 * 败者 false，应用事务整体回滚）；追加史只增不改（授权面 select,insert 同律）。
 * 实现方每方法自含短事务。
 */
public class PostgresRunConfigEpochRepository implements RunConfigEpochRepository {

    private final JdbcClient jdbc;

    public PostgresRunConfigEpochRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean append(UUID runId, long configEpoch, String releaseDigest,
            UUID sourceCommandId, String appliedBy, String reason) {
        int inserted = jdbc.sql("""
                INSERT INTO rca_run_config_epoch (
                    run_id, config_epoch, release_digest, source_command_id,
                    applied_by, reason
                ) VALUES (
                    :runId, :epoch, :digest, :sourceCommandId, :appliedBy, :reason
                )
                ON CONFLICT (run_id, config_epoch) DO NOTHING
                """)
                .param("runId", runId)
                .param("epoch", configEpoch)
                .param("digest", releaseDigest)
                .param("sourceCommandId", sourceCommandId)
                .param("appliedBy", appliedBy)
                .param("reason", reason)
                .update();
        return inserted > 0;
    }

    @Override
    public Optional<EpochRow> findCurrent(UUID runId) {
        return jdbc.sql("""
                SELECT * FROM rca_run_config_epoch
                 WHERE run_id = :runId
                 ORDER BY config_epoch DESC
                 LIMIT 1
                """)
                .param("runId", runId)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<EpochRow> history(UUID runId) {
        return jdbc.sql("""
                SELECT * FROM rca_run_config_epoch
                 WHERE run_id = :runId
                 ORDER BY config_epoch
                """)
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private EpochRow mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        UUID sourceCommandId = rs.getObject("source_command_id", UUID.class);
        String reason = rs.getString("reason");
        return new EpochRow(
                rs.getObject("run_id", UUID.class),
                rs.getLong("config_epoch"),
                rs.getString("release_digest"),
                sourceCommandId,
                rs.getString("applied_by"),
                reason,
                createdAt.toInstant());
    }
}
