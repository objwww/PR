package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.ReportWinnerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * report_generation_winner 的 Postgres 实现（V33，M6-04）。insert-only：只 claim/读，
 * 零 UPDATE/DELETE 路径（授权面同构封死）；PK (incident_id, generation) 冲突 =
 * ON CONFLICT DO NOTHING 返回 false（并发败者语义，非错误）。
 */
public class PostgresReportWinnerRepository implements ReportWinnerRepository {

    private static final String CLAIM_SQL = """
            INSERT INTO report_generation_winner (
                incident_id, generation, winner_report_id, winner_run_id, decided_at
            ) VALUES (
                :incidentId, :generation, :winnerReportId, :winnerRunId, :decidedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String FIND_SQL = """
            SELECT winner_report_id
              FROM report_generation_winner
             WHERE incident_id = :incidentId AND generation = :generation
            """;

    private final JdbcClient jdbc;

    public PostgresReportWinnerRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public boolean claimWinner(UUID incidentId, int generation, UUID reportId, UUID runId,
                               Instant decidedAt) {
        int inserted = jdbc.sql(CLAIM_SQL)
                .param("incidentId", incidentId)
                .param("generation", generation)
                .param("winnerReportId", reportId)
                .param("winnerRunId", runId)
                .param("decidedAt", Timestamp.from(decidedAt))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<UUID> findWinnerReportId(UUID incidentId, int generation) {
        return jdbc.sql(FIND_SQL)
                .param("incidentId", incidentId)
                .param("generation", generation)
                .query((rs, n) -> UUID.fromString(rs.getString("winner_report_id")))
                .optional();
    }
}
