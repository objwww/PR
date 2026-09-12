package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canary 采集样本仓储 PG 实现（B4，V30 canary_evidence_sample）：run_id 唯一 =
 * 幂等锚（DuplicateKey→false，23505 竞态同语义）；jsonb 经 ObjectMapper 字符串化
 * （只写不解析——原始指标不预聚合覆盖，重评读原文）。
 */
public class PostgresCanaryEvidenceSampleRepository implements CanaryEvidenceSampleRepository {

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<>() {
            };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresCanaryEvidenceSampleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insert(SampleRow row) {
        try {
            jdbc.sql("""
                    insert into canary_evidence_sample (run_id, incident_id, stickiness_key,
                        evidence_class, provenance_json, observed_json, created_at)
                    values (:run_id, :incident_id, :stickiness_key, :evidence_class,
                            cast(:provenance as jsonb), cast(:observed as jsonb), :created_at)
                    """)
                    .param("run_id", row.runId())
                    .param("incident_id", row.incidentId())
                    .param("stickiness_key", row.stickinessKey())
                    .param("evidence_class", row.evidenceClass())
                    .param("provenance", json(row.provenance()))
                    .param("observed", json(row.observed()))
                    .param("created_at", Timestamp.from(row.createdAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<SampleRow> findByCollectedBetween(Instant from, Instant to) {
        return jdbc.sql("""
                select run_id, incident_id, stickiness_key, evidence_class,
                       provenance_json, observed_json, created_at
                  from canary_evidence_sample
                 where created_at >= :from and created_at < :to
                 order by created_at, run_id
                """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new SampleRow(
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("incident_id", UUID.class),
                        rs.getString("stickiness_key"),
                        rs.getString("evidence_class"),
                        jsonMap(rs, "provenance_json"),
                        jsonMap(rs, "observed_json"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private String json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canary 样本 jsonb 序列化失败", e);
        }
    }

    private Map<String, Object> jsonMap(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        try {
            return mapper.readValue(rs.getString(column), JSON_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("canary 样本 jsonb 解析失败: " + column, e);
        }
    }
}
