package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V16 证据仓储的 Postgres 实现（AM4 M4-19）。insert 自含短事务：读 run 当前代 →
 * 跨代栅栏 → 落 canonical 字节（TEXT 原样回读）；读路径统一 {@code verify}
 * （payload 字节重算比对，篡改显式拒绝）。scope 的 canonical 文本在 infra 面经
 * Jackson 与 Map 互转（domain 零框架；canonical 形数字归一使 1/1.0 往返稳定）。
 */
public class PostgresEvidenceRepository implements EvidenceRepository {

    private static final String SELECT_COLUMNS = """
            select id, run_id, task_id, evidence_type, schema_version, observed_generation,
                   source, scope, time_start, time_end, payload, payload_digest
              from rca_evidence
            """;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final ObjectMapper objectMapper;

    public PostgresEvidenceRepository(JdbcClient jdbc, TransactionOperations tx,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void insert(EvidenceEnvelope envelope) {
        tx.executeWithoutResult(status -> {
            // 代际栅栏：同 schema_version 不同 generation 在准入面拒绝（run 缺行同显式失败）
            Long runGeneration = jdbc.sql("select generation from rca_run where id = :id")
                    .param("id", envelope.runId())
                    .query(Long.class).optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "run 不存在，证据无法准入: " + envelope.runId()));
            if (runGeneration != envelope.observedGeneration()) {
                throw new IllegalStateException("EVIDENCE_CROSS_GENERATION: 证据代际 "
                        + envelope.observedGeneration() + " != run 当前代 " + runGeneration
                        + "（同 schema_version 不同 generation 拒绝）");
            }
            jdbc.sql("""
                    insert into rca_evidence(id, run_id, task_id, evidence_type,
                        schema_version, observed_generation, source, scope, time_start,
                        time_end, payload, payload_digest)
                    values (:id, :run, :task, :type, :sv, :gen, :source, :scope, :start,
                        :end, :payload, :digest)
                    """)
                    .param("id", envelope.evidenceId()).param("run", envelope.runId())
                    .param("task", envelope.taskId()).param("type", envelope.evidenceType())
                    .param("sv", envelope.schemaVersion())
                    .param("gen", envelope.observedGeneration())
                    .param("source", envelope.source())
                    .param("scope", canonicalScope(envelope.scope()))
                    .param("start", timestamp(envelope.timeStart()))
                    .param("end", timestamp(envelope.timeEnd()))
                    .param("payload", envelope.canonicalPayload())
                    .param("digest", envelope.payloadDigest())
                    .update();
        });
    }

    @Override
    public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
        return jdbc.sql(SELECT_COLUMNS + " where id = :id")
                .param("id", evidenceId)
                .query((rs, n) -> EvidenceEnvelope.verify(mapRow(rs)))
                .optional();
    }

    @Override
    public List<EvidenceEnvelope> findByRunId(UUID runId) {
        return jdbc.sql(SELECT_COLUMNS + " where run_id = :run order by created_at, id")
                .param("run", runId)
                .query((rs, n) -> EvidenceEnvelope.verify(mapRow(rs)))
                .list();
    }

    private EvidenceEnvelope mapRow(ResultSet rs) throws SQLException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = objectMapper.readValue(rs.getString("scope"), Map.class);
            return new EvidenceEnvelope(
                    rs.getObject("id", UUID.class),
                    rs.getObject("run_id", UUID.class),
                    rs.getObject("task_id", UUID.class),
                    rs.getString("evidence_type"),
                    rs.getString("schema_version"),
                    rs.getLong("observed_generation"),
                    rs.getString("source"),
                    scope,
                    toInstant(rs.getTimestamp("time_start")),
                    toInstant(rs.getTimestamp("time_end")),
                    rs.getString("payload"),
                    rs.getString("payload_digest"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("scope canonical 文本反序列化失败", e);
        }
    }

    private String canonicalScope(Map<String, Object> scope) {
        try {
            // 先转 canonical 形（排序+数字归一），保证字节往返稳定
            String raw = objectMapper.writeValueAsString(scope);
            Object parsed = objectMapper.readValue(raw, Object.class);
            return com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                    .canonicalize(parsed);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("scope 无法序列化", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
