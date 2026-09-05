package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_investigation_result 的 Postgres 实现（V9；M3-04）。
 *
 * <p>栅栏（FUT-50）在 SQL 内原子判定：写入行携带的 observed_generation 必须与
 * rca_run.generation 一致（INSERT...SELECT WHERE EXISTS / UPDATE...WHERE 子查询），
 * 晚到旧代写入 0 行被拒——不做多层 JOIN 推导。
 */
public class PostgresInvestigationResultRepository implements InvestigationResultRepository {

    private static final String INSERT_STARTED_SQL = """
            INSERT INTO rca_investigation_result (
                id, attempt_id, run_id, observed_generation, schema_version,
                execution_status, validation_status, created_at
            )
            SELECT :id, :attemptId, :runId, :observedGeneration, :schemaVersion,
                   'STARTED', 'NOT_VALIDATED', :createdAt
            WHERE EXISTS (SELECT 1 FROM rca_run r
                          WHERE r.id = :runId AND r.generation = :observedGeneration)
            """;

    private static final String FINISH_SQL = """
            UPDATE rca_investigation_result SET
                execution_status = :executionStatus,
                validation_status = :validationStatus,
                validation_errors = CAST(:validationErrors AS jsonb),
                package_json = CAST(:packageJson AS jsonb),
                raw_artifact_ref = :rawArtifactRef,
                raw_digest = :rawDigest,
                payload_digest = :payloadDigest,
                model = :model,
                usage_json = CAST(:usageJson AS jsonb),
                finished_at = :finishedAt
            WHERE id = :id AND execution_status = 'STARTED'
              AND observed_generation = (SELECT generation FROM rca_run WHERE id = run_id)
            """;

    private final JdbcClient jdbc;

    public PostgresInvestigationResultRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public InvestigationResult insertStartedIfAbsent(InvestigationResult started) {
        if (started.executionStatus() != ExecutionStatus.STARTED) {
            throw new IllegalArgumentException("insertStartedIfAbsent 只接受 STARTED 行");
        }
        try {
            int inserted = jdbc.sql(INSERT_STARTED_SQL)
                    .param("id", started.id())
                    .param("attemptId", started.attemptId())
                    .param("runId", started.runId())
                    .param("observedGeneration", started.observedGeneration())
                    .param("schemaVersion", started.schemaVersion())
                    .param("createdAt", ts(started.createdAt()))
                    .update();
            if (inserted == 0) {
                // generation 栅栏不匹配 = 铸造就是晚到的（理论不可达，诚实抛出）
                throw new IllegalStateException(
                        "InvestigationResult STARTED 写入被 generation 栅栏拒绝: run="
                                + started.runId() + " observed=" + started.observedGeneration());
            }
            return started;
        } catch (DuplicateKeyException e) {
            // 幂等锚：attempt 已有记录（崩溃重放）——返回现行，不覆盖
            return findByAttemptId(started.attemptId()).orElseThrow();
        }
    }

    @Override
    public boolean finishTerminal(InvestigationResult terminal) {
        if (terminal.executionStatus() == ExecutionStatus.STARTED) {
            throw new IllegalArgumentException("finishTerminal 不接受 STARTED");
        }
        return jdbc.sql(FINISH_SQL)
                .param("executionStatus", terminal.executionStatus().name())
                .param("validationStatus", terminal.validationStatus().name())
                .param("validationErrors", toJson(terminal.validationErrors()))
                .param("packageJson", terminal.packageJson())
                .param("rawArtifactRef", terminal.rawArtifactRef())
                .param("rawDigest", hash(terminal.rawDigest()))
                .param("payloadDigest", hash(terminal.payloadDigest()))
                .param("model", terminal.model())
                .param("usageJson", terminal.usageJson())
                .param("finishedAt", ts(terminal.finishedAt()))
                .param("id", terminal.id())
                .update() > 0;
    }

    @Override
    public List<InvestigationResult> findHangingStarted(Instant olderThan) {
        return jdbc.sql("""
                        SELECT * FROM rca_investigation_result
                        WHERE execution_status = 'STARTED' AND created_at < :threshold
                        """)
                .param("threshold", Timestamp.from(olderThan))
                .query(this::mapRow)
                .list();
    }

    @Override
    public Optional<InvestigationResult> findByAttemptId(UUID attemptId) {
        return jdbc.sql("SELECT * FROM rca_investigation_result WHERE attempt_id = :attemptId")
                .param("attemptId", attemptId)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<InvestigationResult> findByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM rca_investigation_result"
                        + " WHERE run_id = :runId ORDER BY created_at")
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private InvestigationResult mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new InvestigationResult(
                rs.getObject("id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("observed_generation"),
                rs.getInt("schema_version"),
                ExecutionStatus.valueOf(rs.getString("execution_status")),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                toStringList(rs.getString("validation_errors")),
                rs.getString("package_json"),
                rs.getString("raw_artifact_ref"),
                digest(rs.getString("raw_digest")),
                digest(rs.getString("payload_digest")),
                rs.getString("model"),
                rs.getString("usage_json"),
                rs.getTimestamp("created_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** List<String> → JSON 数组文本（jsonb CAST 入参）；null/空透传 */
    private static String toJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 数组文本 → List<String>（读侧容错：非数组形态当空链） */
    private static List<String> toStringList(String jsonScalar) {
        if (jsonScalar == null) {
            return List.of();
        }
        try {
            return JSON_MAPPER.readValue(jsonScalar,
                    JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String hash(Digest digest) {
        return digest == null ? null : digest.value();
    }

    private static Digest digest(String value) {
        return value == null ? null : new Digest(value);
    }
}
