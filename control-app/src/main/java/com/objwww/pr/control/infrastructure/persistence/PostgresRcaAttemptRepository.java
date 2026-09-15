package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_attempt 的 Postgres 实现（V1 step_attempt 同构）。
 * sampling_fingerprint（M5-04/V23）：Map ↔ jsonb；insert 为 null（STARTED 行），
 * 终态 update 随收尾事务写入。
 */
public class PostgresRcaAttemptRepository implements RcaAttemptRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public PostgresRcaAttemptRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(RcaAttempt attempt) {
        jdbc.sql("""
                INSERT INTO rca_attempt (
                    id, task_id, attempt_no, lease_epoch, worker_id,
                    status, error_class, error_code, error_detail, started_at,
                    sampling_fingerprint,
                    last_activity_at, last_meaningful_progress_at
                ) VALUES (
                    :id, :taskId, :attemptNo, :leaseEpoch, :workerId,
                    :status, :errorClass, :errorCode, CAST(:errorDetail AS jsonb), :startedAt,
                    CAST(:samplingFingerprint AS jsonb),
                    :startedAt, :startedAt
                )
                """)
                .param("id", attempt.id())
                .param("taskId", attempt.taskId())
                .param("attemptNo", attempt.attemptNo())
                .param("leaseEpoch", attempt.leaseEpoch())
                .param("workerId", attempt.workerId())
                .param("status", attempt.status().name())
                .param("errorClass", attempt.errorClass())
                .param("errorCode", attempt.errorCode())
                .param("errorDetail", JsonbText.encode(attempt.errorDetail()))
                .param("startedAt", Timestamp.from(attempt.startedAt()))
                .param("samplingFingerprint", fingerprintJson(attempt.samplingFingerprint()))
                .update();
    }

    @Override
    public boolean update(RcaAttempt attempt) {
        return jdbc.sql("""
                UPDATE rca_attempt SET
                    status = :status, error_class = :errorClass,
                    error_code = :errorCode, error_detail = CAST(:errorDetail AS jsonb),
                    finished_at = :finishedAt,
                    sampling_fingerprint = CAST(:samplingFingerprint AS jsonb)
                 WHERE id = :id
                """)
                .param("status", attempt.status().name())
                .param("errorClass", attempt.errorClass())
                .param("errorCode", attempt.errorCode())
                .param("errorDetail", JsonbText.encode(attempt.errorDetail()))
                .param("finishedAt", ts(attempt.finishedAt()))
                .param("samplingFingerprint", fingerprintJson(attempt.samplingFingerprint()))
                .param("id", attempt.id())
                .update() > 0;
    }

    @Override
    public List<RcaAttempt> findByTaskId(UUID taskId) {
        return jdbc.sql("SELECT * FROM rca_attempt WHERE task_id = :taskId ORDER BY started_at")
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public boolean markActivityByTask(UUID taskId, java.time.Instant at) {
        return jdbc.sql("""
                UPDATE rca_attempt SET last_activity_at = :at
                 WHERE task_id = :taskId AND status = 'STARTED'
                """)
                .param("at", ts(at))
                .param("taskId", taskId)
                .update() > 0;
    }

    @Override
    public boolean markMeaningfulProgressByTask(UUID taskId, java.time.Instant at) {
        return jdbc.sql("""
                UPDATE rca_attempt
                   SET last_activity_at = :at, last_meaningful_progress_at = :at
                 WHERE task_id = :taskId AND status = 'STARTED'
                """)
                .param("at", ts(at))
                .param("taskId", taskId)
                .update() > 0;
    }

    @Override
    public java.util.Optional<AttemptProgress> findStartedProgressByTaskId(UUID taskId) {
        return jdbc.sql("""
                SELECT id, task_id, attempt_no, lease_epoch, started_at,
                       last_activity_at, last_meaningful_progress_at
                  FROM rca_attempt
                 WHERE task_id = :taskId AND status = 'STARTED'
                 ORDER BY started_at DESC
                 LIMIT 1
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> {
                    Timestamp startedAt = rs.getTimestamp("started_at");
                    Timestamp lastActivity = rs.getTimestamp("last_activity_at");
                    Timestamp lastProgress = rs.getTimestamp("last_meaningful_progress_at");
                    return new AttemptProgress(
                            rs.getObject("id", UUID.class),
                            rs.getObject("task_id", UUID.class),
                            rs.getInt("attempt_no"),
                            rs.getLong("lease_epoch"),
                            startedAt.toInstant(),
                            lastActivity == null ? null : lastActivity.toInstant(),
                            lastProgress == null ? null : lastProgress.toInstant());
                })
                .optional();
    }

    private static String fingerprintJson(Map<String, Object> fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(fingerprint);
        } catch (Exception e) {
            throw new IllegalStateException("sampling_fingerprint 序列化失败", e);
        }
    }

    private static Map<String, Object> fingerprintMap(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("sampling_fingerprint 反序列化失败", e);
        }
    }

    private static Timestamp ts(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private RcaAttempt mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new RcaAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getLong("lease_epoch"),
                rs.getString("worker_id"),
                RcaAttemptStatus.valueOf(rs.getString("status")),
                rs.getString("error_class"),
                rs.getString("error_code"),
                JsonbText.decode(rs.getString("error_detail")),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                fingerprintMap(rs.getString("sampling_fingerprint")));
    }
}
