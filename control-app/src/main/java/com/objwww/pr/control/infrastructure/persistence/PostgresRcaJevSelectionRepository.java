package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaJevSelection;
import com.objwww.pr.control.alert.domain.repository.RcaJevSelectionPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_jev_selection 的 Postgres 实现（JE-02，V166）。refs/probabilities 以 jsonb
 * 落库（由 ObjectMapper 序列化）；每方法自含短事务，append 失败由调用方有界降级。
 */
public class PostgresRcaJevSelectionRepository implements RcaJevSelectionPort {

    private static final int DEFAULT_READ_LIMIT = 50;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresRcaJevSelectionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void append(RcaJevSelection row) {
        jdbc.sql("""
                INSERT INTO rca_jev_selection (
                    id, run_id, task_id, mode, applied,
                    pool_refs, protected_refs, selected_refs, omitted_refs,
                    probabilities, model_call_id, policy_digest, latency_ms, created_at
                ) VALUES (
                    :id, :runId, :taskId, :mode, :applied,
                    CAST(:poolRefs AS jsonb), CAST(:protectedRefs AS jsonb),
                    CAST(:selectedRefs AS jsonb), CAST(:omittedRefs AS jsonb),
                    CAST(:probabilities AS jsonb), :modelCallId, :policyDigest,
                    :latencyMs, :createdAt
                )
                """)
                .param("id", row.id())
                .param("runId", row.runId())
                .param("taskId", row.taskId())
                .param("mode", row.mode())
                .param("applied", row.applied())
                .param("poolRefs", json(row.poolRefs()))
                .param("protectedRefs", json(row.protectedRefs()))
                .param("selectedRefs", json(row.selectedRefs()))
                .param("omittedRefs", json(row.omittedRefs()))
                .param("probabilities", json(row.probabilities()))
                .param("modelCallId", row.modelCallId())
                .param("policyDigest", row.policyDigest())
                .param("latencyMs", row.latencyMs())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .update();
    }

    @Override
    public List<RcaJevSelection> findByRun(UUID runId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return jdbc.sql("""
                        SELECT s.id, s.run_id, s.task_id, s.mode, s.applied,
                               s.pool_refs, s.protected_refs, s.selected_refs,
                               s.omitted_refs, s.probabilities, s.model_call_id,
                               s.policy_digest, s.latency_ms, s.created_at,
                               (m.usage->>'total_tokens')::bigint AS total_tokens
                          FROM rca_jev_selection s
                          LEFT JOIN rca_model_call m ON m.id = s.model_call_id
                         WHERE s.run_id = :runId
                         ORDER BY s.created_at DESC, s.id
                         LIMIT :limit
                        """)
                .param("runId", runId)
                .param("limit", capped)
                .query((rs, n) -> new RcaJevSelection(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("mode"),
                        rs.getBoolean("applied"),
                        refs(rs.getString("pool_refs")),
                        refs(rs.getString("protected_refs")),
                        refs(rs.getString("selected_refs")),
                        refs(rs.getString("omitted_refs")),
                        probabilities(rs.getString("probabilities")),
                        rs.getObject("model_call_id", UUID.class),
                        rs.getString("policy_digest"),
                        rs.getObject("latency_ms", Long.class) == null
                                ? null : rs.getLong("latency_ms"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("total_tokens", Long.class)))
                .list();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Jev 选材 jsonb 序列化失败", e);
        }
    }

    private List<String> refs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, Double> probabilities(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json,
                    new TypeReference<Map<String, Double>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
