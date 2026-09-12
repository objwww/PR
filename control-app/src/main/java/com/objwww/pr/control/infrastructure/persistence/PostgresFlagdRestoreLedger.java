package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FlagdRestoreLedger} 的 Postgres 实现（DR-05，V95 flagd_restore_ledger；
 * JdbcClient 手写 SQL，沿 PostgresDrillJobRepository 惯例）。
 * eval_app 单一读写身份：insert + select + 列级 update(state/state_reason/updated_at)
 * ——正文列（flag/原值/写入值/截止）落账即冻结，零 update 开口。
 */
public class PostgresFlagdRestoreLedger implements FlagdRestoreLedger {

    private static final String COLS =
            "id, flag, scenario_id, round_no, original_variant, original_generation,"
                    + " applied_variant, applied_generation, baseline_variant,"
                    + " deadline_at, state, state_reason, created_at, updated_at";

    /** 可恢复面（OPEN/UNKNOWN；RESTORED/CONFLICT 终态不回开） */
    private static final String RESTORABLE = "('OPEN','UNKNOWN')";

    private final JdbcClient jdbc;

    public PostgresFlagdRestoreLedger(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void recordActivation(FlagdRestoreRecord r) {
        jdbc.sql("""
                        INSERT INTO flagd_restore_ledger (
                            id, flag, scenario_id, round_no, original_variant,
                            original_generation, applied_variant, applied_generation,
                            baseline_variant, deadline_at, state, created_at, updated_at
                        ) VALUES (
                            :id, :flag, :scenarioId, :roundNo, :originalVariant,
                            :originalGeneration, :appliedVariant, :appliedGeneration,
                            :baselineVariant, :deadlineAt, 'OPEN', :createdAt, :updatedAt
                        )
                        """)
                .param("id", r.id())
                .param("flag", r.flag())
                .param("scenarioId", r.scenarioId())
                .param("roundNo", r.roundNo())
                .param("originalVariant", r.originalVariant())
                .param("originalGeneration", r.originalGeneration())
                .param("appliedVariant", r.appliedVariant())
                .param("appliedGeneration", r.appliedGeneration())
                .param("baselineVariant", r.baselineVariant())
                .param("deadlineAt", Timestamp.from(r.deadlineAt()))
                .param("createdAt", Timestamp.from(r.createdAt()))
                .param("updatedAt", Timestamp.from(r.updatedAt()))
                .update();
    }

    @Override
    public Optional<FlagdRestoreRecord> findRestorableByFlag(String flag) {
        return jdbc.sql("SELECT " + COLS + " FROM flagd_restore_ledger"
                        + " WHERE flag = :flag AND state IN " + RESTORABLE
                        + " ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("flag", flag).query(this::map).optional();
    }

    /** 收口 CAS：仅可恢复面可迁移（driver 与 sweeper 并发恰一方生效，重复收口幂等落空） */
    @Override
    public boolean close(UUID id, FlagdRestoreRecord.State to, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE flagd_restore_ledger SET state = :to, state_reason = :reason,
                            updated_at = :at
                        WHERE id = :id AND state IN """ + " " + RESTORABLE)
                .param("to", to.name())
                .param("reason", reason)
                .param("at", Timestamp.from(now))
                .param("id", id)
                .update() > 0;
    }

    @Override
    public List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now) {
        return jdbc.sql("SELECT " + COLS + " FROM flagd_restore_ledger"
                        + " WHERE state IN " + RESTORABLE + " AND deadline_at < :now"
                        + " ORDER BY deadline_at, id")
                .param("now", Timestamp.from(now))
                .query(this::map).list();
    }

    private FlagdRestoreRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new FlagdRestoreRecord(
                rs.getObject("id", UUID.class),
                rs.getString("flag"),
                rs.getString("scenario_id"),
                rs.getInt("round_no"),
                rs.getString("original_variant"),
                rs.getString("original_generation"),
                rs.getString("applied_variant"),
                rs.getString("applied_generation"),
                rs.getString("baseline_variant"),
                rs.getTimestamp("deadline_at").toInstant(),
                FlagdRestoreRecord.State.valueOf(rs.getString("state")),
                rs.getString("state_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
