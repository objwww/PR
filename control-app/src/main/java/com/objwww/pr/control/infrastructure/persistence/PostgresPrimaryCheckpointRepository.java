package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_primary_checkpoint 的 Postgres 实现（R7-X4，V47）。task_id 主键 = 一主任务
 * 一检查点，upsert 全量落账；phase CAS 单语句自含（WHERE phase = :from 判 affected 行数）。
 */
public class PostgresPrimaryCheckpointRepository implements PrimaryCheckpointRepository {

    private static final TypeReference<List<java.util.Map<String, Object>>> CLAIMS =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresPrimaryCheckpointRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void upsert(PrimaryCheckpoint cp) {
        jdbc.sql("""
                INSERT INTO rca_primary_checkpoint (
                    task_id, run_id, round_id, phase,
                    decision_seq, steps_used, batches_used,
                    input_snapshot_digest, final_claims, final_missing_information, updated_at
                ) VALUES (
                    :taskId, :runId, :roundId, :phase,
                    :decisionSeq, :stepsUsed, :batchesUsed,
                    :snapshotDigest, cast(:finalClaims as jsonb),
                    cast(:missing as jsonb), :updatedAt
                )
                ON CONFLICT (task_id) DO UPDATE SET
                    round_id = EXCLUDED.round_id,
                    phase = EXCLUDED.phase,
                    decision_seq = EXCLUDED.decision_seq,
                    steps_used = EXCLUDED.steps_used,
                    batches_used = EXCLUDED.batches_used,
                    input_snapshot_digest = EXCLUDED.input_snapshot_digest,
                    final_claims = EXCLUDED.final_claims,
                    final_missing_information = EXCLUDED.final_missing_information,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("taskId", cp.taskId())
                .param("runId", cp.runId())
                .param("roundId", cp.roundId())
                .param("phase", cp.phase().name())
                .param("decisionSeq", cp.decisionSeq())
                .param("stepsUsed", cp.stepsUsed())
                .param("batchesUsed", cp.batchesUsed())
                .param("snapshotDigest", cp.inputSnapshotDigest())
                .param("finalClaims", jsonOf(cp.finalClaims()))
                .param("missing", jsonOf(cp.finalMissingInformation()))
                .param("updatedAt", Timestamp.from(cp.updatedAt()))
                .update();
    }

    @Override
    public Optional<PrimaryCheckpoint> findByTask(UUID taskId) {
        List<PrimaryCheckpoint> rows = jdbc.sql("""
                SELECT * FROM rca_primary_checkpoint WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean transitionPhase(UUID taskId, PrimaryCheckpoint.Phase from,
            PrimaryCheckpoint.Phase to) {
        int updated = jdbc.sql("""
                UPDATE rca_primary_checkpoint
                   SET phase = :to, updated_at = now()
                 WHERE task_id = :taskId AND phase = :from
                """)
                .param("taskId", taskId)
                .param("from", from.name())
                .param("to", to.name())
                .update();
        return updated > 0;
    }

    private PrimaryCheckpoint mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        String claimsJson = rs.getString("final_claims");
        String missingJson = rs.getString("final_missing_information");
        return new PrimaryCheckpoint(
                rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("round_id"),
                PrimaryCheckpoint.Phase.valueOf(rs.getString("phase")),
                rs.getInt("decision_seq"),
                rs.getInt("steps_used"),
                rs.getInt("batches_used"),
                rs.getString("input_snapshot_digest"),
                claimsJson == null ? List.of() : claimsOf(claimsJson),
                missingJson == null ? List.of() : stringsOf(missingJson),
                updatedAt.toInstant());
    }

    private String jsonOf(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<java.util.Map<String, Object>> claimsOf(String json) {
        try {
            return mapper.readValue(json, CLAIMS);
        } catch (Exception e) {
            throw new IllegalStateException("final_claims 解析失败: " + e.getMessage(), e);
        }
    }

    private List<String> stringsOf(String json) {
        try {
            return mapper.readValue(json, STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("final_missing_information 解析失败: "
                    + e.getMessage(), e);
        }
    }
}
