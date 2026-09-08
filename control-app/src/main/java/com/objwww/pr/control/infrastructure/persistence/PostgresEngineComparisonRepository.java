package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * engine_comparison 的 Postgres 实现（V32，M6-02）。append-only：只 append 与
 * findByNativeRunId，零 UPDATE/DELETE 路径（授权面同构封死）；uq_ec_pair 冲突 =
 * 同对照幂等（返回 false，非错误）。jsonb 载荷 Map 序列化在仓储边界完成。
 */
public class PostgresEngineComparisonRepository implements EngineComparisonRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APPEND_SQL = """
            INSERT INTO engine_comparison (
                native_run_id, comparison_key, shadow_exec_ref, snapshot_digest,
                holmes_outcome, native_outcome, disagree_flags, noise_baseline,
                cost_compare, created_at
            ) VALUES (
                :nativeRunId, :comparisonKey, :shadowExecRef, :snapshotDigest,
                CAST(:holmesOutcome AS jsonb), CAST(:nativeOutcome AS jsonb),
                CAST(:disagreeFlags AS jsonb), CAST(:noiseBaseline AS jsonb),
                CAST(:costCompare AS jsonb), :createdAt
            )
            ON CONFLICT ON CONSTRAINT uq_ec_pair DO NOTHING
            """;

    private static final String FIND_SQL = """
            SELECT native_run_id, comparison_key, shadow_exec_ref, snapshot_digest,
                   holmes_outcome, native_outcome, disagree_flags, noise_baseline,
                   cost_compare, created_at
              FROM engine_comparison
             WHERE native_run_id = :nativeRunId
             ORDER BY created_at, id
            """;

    private final JdbcClient jdbc;

    public PostgresEngineComparisonRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public boolean append(ComparisonRow row) {
        int inserted = jdbc.sql(APPEND_SQL)
                .param("nativeRunId", row.nativeRunId())
                .param("comparisonKey", row.comparisonKey())
                .param("shadowExecRef", row.shadowExecRef())
                .param("snapshotDigest", row.snapshotDigest())
                .param("holmesOutcome", writeJson(row.holmesOutcome()))
                .param("nativeOutcome", writeJson(row.nativeOutcome()))
                .param("disagreeFlags", writeJson(row.disagreeFlags()))
                .param("noiseBaseline", writeJson(row.noiseBaseline()))
                .param("costCompare", writeJson(row.costCompare()))
                .param("createdAt", Timestamp.from(java.time.Instant.now()))
                .update();
        return inserted == 1;
    }

    @Override
    public List<ComparisonRow> findByNativeRunId(UUID nativeRunId) {
        return jdbc.sql(FIND_SQL)
                .param("nativeRunId", nativeRunId)
                .query((rs, n) -> new ComparisonRow(
                        UUID.fromString(rs.getString("native_run_id")),
                        rs.getString("comparison_key"),
                        rs.getString("shadow_exec_ref"),
                        rs.getString("snapshot_digest"),
                        readJson(rs.getString("holmes_outcome")),
                        readJson(rs.getString("native_outcome")),
                        readFlagList(rs.getString("disagree_flags")),
                        readJson(rs.getString("noise_baseline")),
                        readJson(rs.getString("cost_compare"))))
                .list();
    }

    // ------------------------------------------------------------------ 内部

    private static String writeJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("engine_comparison jsonb 序列化失败", e);
        }
    }

    private static String writeJson(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("engine_comparison jsonb 序列化失败", e);
        }
    }

    private static LinkedHashMap<String, Object> readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("engine_comparison jsonb 反序列化失败", e);
        }
    }

    private static List<Map<String, Object>> readFlagList(String raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            List<LinkedHashMap<String, Object>> parsed = JSON.readValue(raw,
                    new TypeReference<List<LinkedHashMap<String, Object>>>() {
                    });
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (LinkedHashMap<String, Object> entry : parsed) {
                out.add(new LinkedHashMap<>(entry));
            }
            return List.copyOf(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("engine_comparison disagree_flags 反序列化失败", e);
        }
    }
}
