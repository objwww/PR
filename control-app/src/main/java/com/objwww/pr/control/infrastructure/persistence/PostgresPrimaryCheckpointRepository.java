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
                    input_snapshot_digest, memory_id, memory_digest,
                    final_claims, final_missing_information,
                    last_error, updated_at
                ) VALUES (
                    :taskId, :runId, :roundId, :phase,
                    :decisionSeq, :stepsUsed, :batchesUsed,
                    :snapshotDigest, :memoryId, :memoryDigest,
                    cast(:finalClaims as jsonb),
                    cast(:missing as jsonb), :lastError, :updatedAt
                )
                ON CONFLICT (task_id) DO UPDATE SET
                    round_id = EXCLUDED.round_id,
                    phase = EXCLUDED.phase,
                    decision_seq = EXCLUDED.decision_seq,
                    steps_used = EXCLUDED.steps_used,
                    batches_used = EXCLUDED.batches_used,
                    input_snapshot_digest = EXCLUDED.input_snapshot_digest,
                    memory_id = EXCLUDED.memory_id,
                    memory_digest = EXCLUDED.memory_digest,
                    final_claims = EXCLUDED.final_claims,
                    final_missing_information = EXCLUDED.final_missing_information,
                    last_error = EXCLUDED.last_error,
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
                .param("memoryId", cp.memoryId())
                .param("memoryDigest", cp.memoryDigest())
                .param("finalClaims", jsonOf(cp.finalClaims()))
                .param("missing", jsonOf(cp.finalMissingInformation()))
                .param("lastError", cp.lastError())
                .param("updatedAt", Timestamp.from(cp.updatedAt()))
                .update();
    }

    @Override
    public boolean insertIfAbsent(PrimaryCheckpoint initial) {
        // 单语句幂等（不存在才插）：竞态下缺席者胜，不走"读后写"窗口
        int inserted = jdbc.sql("""
                INSERT INTO rca_primary_checkpoint (
                    task_id, run_id, round_id, phase,
                    decision_seq, steps_used, batches_used,
                    input_snapshot_digest, memory_id, memory_digest,
                    final_claims, final_missing_information,
                    last_error, updated_at
                ) VALUES (
                    :taskId, :runId, :roundId, :phase,
                    0, 0, 0, null, null, null,
                    cast('[]' as jsonb), cast('[]' as jsonb), null, :updatedAt
                )
                ON CONFLICT (task_id) DO NOTHING
                """)
                .param("taskId", initial.taskId())
                .param("runId", initial.runId())
                .param("roundId", initial.roundId())
                .param("phase", initial.phase().name())
                .param("updatedAt", Timestamp.from(initial.updatedAt()))
                .update();
        return inserted > 0;
    }

    @Override
    public Optional<PrimaryCheckpoint> findByTaskForUpdate(UUID taskId) {
        List<PrimaryCheckpoint> rows = jdbc.sql("""
                SELECT * FROM rca_primary_checkpoint WHERE task_id = :taskId FOR UPDATE
                """)
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public CommitState findCommitStateForUpdate(UUID taskId) {
        List<Object[]> rows = jdbc.sql("""
                SELECT * FROM rca_primary_checkpoint WHERE task_id = :taskId FOR UPDATE
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new Object[] {
                        mapRow(rs, rowNum),
                        rs.getString("last_action_key"),
                        rs.getString("last_action_digest")})
                .list();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        return new CommitState((PrimaryCheckpoint) row[0],
                (String) row[1], (String) row[2]);
    }

    @Override
    public long updateGuarded(PrimaryCheckpoint next, long expectedRevision,
            String actionKey, String actionDigest) {
        return jdbc.sql("""
                UPDATE rca_primary_checkpoint SET
                    round_id = :roundId, phase = :phase,
                    decision_seq = :decisionSeq, steps_used = :stepsUsed,
                    batches_used = :batchesUsed,
                    input_snapshot_digest = :snapshotDigest,
                    memory_id = :memoryId, memory_digest = :memoryDigest,
                    final_claims = cast(:finalClaims as jsonb),
                    final_missing_information = cast(:missing as jsonb),
                    last_error = :lastError, updated_at = now(),
                    revision = revision + 1,
                    schema_version = :schemaVersion,
                    current_context_digest = :contextDigest,
                    current_summary_id = :summaryId,
                    last_action_key = :actionKey,
                    last_action_digest = :actionDigest
                WHERE task_id = :taskId AND revision = :expectedRevision
                """)
                .param("taskId", next.taskId())
                .param("roundId", next.roundId())
                .param("phase", next.phase().name())
                .param("decisionSeq", next.decisionSeq())
                .param("stepsUsed", next.stepsUsed())
                .param("batchesUsed", next.batchesUsed())
                .param("snapshotDigest", next.inputSnapshotDigest())
                .param("memoryId", next.memoryId())
                .param("memoryDigest", next.memoryDigest())
                .param("finalClaims", jsonOf(next.finalClaims()))
                .param("missing", jsonOf(next.finalMissingInformation()))
                .param("lastError", next.lastError())
                .param("schemaVersion", next.schemaVersion())
                .param("contextDigest", next.currentContextDigest())
                .param("summaryId", next.currentSummaryId())
                .param("actionKey", actionKey)
                .param("actionDigest", actionDigest)
                .param("expectedRevision", expectedRevision)
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
                rs.getObject("memory_id", UUID.class),
                rs.getString("memory_digest"),
                claimsJson == null ? List.of() : claimsOf(claimsJson),
                missingJson == null ? List.of() : stringsOf(missingJson),
                rs.getString("last_error"),
                updatedAt.toInstant(),
                rs.getLong("revision"),
                rs.getInt("schema_version"),
                rs.getString("current_context_digest"),
                rs.getObject("current_summary_id", UUID.class));
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
