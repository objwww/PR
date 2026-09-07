package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * operator_case 的 Postgres 实现（M5-11；V26）。
 *
 * <p>update 只写可变列（status/owner/activities/audits/resolution/revision/updated_at），
 * 快照列与来源列零开口（快照冻结 + 结案不改历史的 SQL 面）；CAS = WHERE revision=:expected。
 * activities/audits/resolution/evidence_refs 以 jsonb 数组/对象内嵌（P4 mock 形状，
 * Instant 一律 ISO-8601 字符串）。
 */
public class PostgresOperatorCaseRepository implements OperatorCaseRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private static final String INSERT_SQL = """
            INSERT INTO operator_case (
                id, tenant, fingerprint, subject, priority, reason_code, status, owner,
                run_id, task_id, incident_type, snapshot_digest, observed_generation,
                evidence_refs, activities, audits, resolution,
                first_seen, ack_due, resolve_due, revision, created_at, updated_at
            ) VALUES (
                :id, :tenant, :fingerprint, :subject, :priority, :reasonCode, :status, :owner,
                :runId, :taskId, :incidentType, :snapshotDigest, :observedGeneration,
                CAST(:evidenceRefs AS jsonb), CAST(:activities AS jsonb), CAST(:audits AS jsonb),
                CAST(:resolution AS jsonb),
                :firstSeen, :ackDue, :resolveDue, :revision, :createdAt, :updatedAt
            )
            """;

    private static final String UPDATE_SQL = """
            UPDATE operator_case SET
                status = :status, owner = :owner,
                activities = CAST(:activities AS jsonb), audits = CAST(:audits AS jsonb),
                resolution = CAST(:resolution AS jsonb),
                revision = :revision, updated_at = :updatedAt
             WHERE id = :id AND revision = :expectedRevision
            """;

    private final JdbcClient jdbc;

    public PostgresOperatorCaseRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(OperatorCase operatorCase) {
        jdbc.sql(INSERT_SQL)
                .param("id", operatorCase.id())
                .param("tenant", operatorCase.tenant())
                .param("fingerprint", operatorCase.fingerprint())
                .param("subject", operatorCase.subject())
                .param("priority", operatorCase.priority())
                .param("reasonCode", operatorCase.reasonCode())
                .param("status", operatorCase.status().name())
                .param("owner", operatorCase.owner())
                .param("runId", operatorCase.runId())
                .param("taskId", operatorCase.taskId())
                .param("incidentType", operatorCase.incidentType())
                .param("snapshotDigest", digestOrNull(operatorCase.snapshotDigest()))
                .param("observedGeneration", operatorCase.observedGeneration())
                .param("evidenceRefs", json(operatorCase.evidenceRefs()))
                .param("activities", json(operatorCase.activities().stream()
                        .map(PostgresOperatorCaseRepository::toMap).toList()))
                .param("audits", json(operatorCase.audits().stream()
                        .map(PostgresOperatorCaseRepository::toMap).toList()))
                .param("resolution", operatorCase.resolution() == null
                        ? null : json(toMap(operatorCase.resolution())))
                .param("firstSeen", ts(operatorCase.firstSeen()))
                .param("ackDue", ts(operatorCase.ackDue()))
                .param("resolveDue", ts(operatorCase.resolveDue()))
                .param("revision", operatorCase.revision())
                .param("createdAt", ts(operatorCase.createdAt()))
                .param("updatedAt", ts(operatorCase.updatedAt()))
                .update();
    }

    @Override
    public Optional<OperatorCase> lockByTenantAndFingerprint(String tenant, String fingerprint) {
        List<OperatorCase> rows = jdbc.sql("""
                        SELECT * FROM operator_case
                         WHERE tenant = :tenant AND fingerprint = :fingerprint
                           FOR UPDATE
                        """)
                .param("tenant", tenant)
                .param("fingerprint", fingerprint)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<OperatorCase> findById(UUID id) {
        List<OperatorCase> rows = jdbc.sql("SELECT * FROM operator_case WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<OperatorCase> findAll() {
        return jdbc.sql("SELECT * FROM operator_case ORDER BY created_at, id")
                .query(this::mapRow)
                .list();
    }

    @Override
    public boolean update(OperatorCase operatorCase, long expectedRevision) {
        return jdbc.sql(UPDATE_SQL)
                .param("status", operatorCase.status().name())
                .param("owner", operatorCase.owner())
                .param("activities", json(operatorCase.activities().stream()
                        .map(PostgresOperatorCaseRepository::toMap).toList()))
                .param("audits", json(operatorCase.audits().stream()
                        .map(PostgresOperatorCaseRepository::toMap).toList()))
                .param("resolution", operatorCase.resolution() == null
                        ? null : json(toMap(operatorCase.resolution())))
                .param("revision", operatorCase.revision())
                .param("updatedAt", ts(operatorCase.updatedAt()))
                .param("id", operatorCase.id())
                .param("expectedRevision", expectedRevision)
                .update() == 1;
    }

    // ------------------------------------------------------------------ 行投影

    private OperatorCase mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OperatorCase(
                rs.getObject("id", UUID.class),
                rs.getString("tenant"),
                rs.getString("fingerprint"),
                rs.getString("subject"),
                rs.getString("priority"),
                rs.getString("reason_code"),
                CaseStatus.valueOf(rs.getString("status")),
                rs.getString("owner"),
                rs.getObject("run_id", UUID.class),
                rs.getString("task_id"),
                rs.getString("incident_type"),
                rs.getString("snapshot_digest") == null
                        ? null : new Digest(rs.getString("snapshot_digest")),
                rs.getInt("observed_generation"),
                readList(rs.getString("evidence_refs")).stream().map(Object::toString).toList(),
                readList(rs.getString("activities")).stream()
                        .map(PostgresOperatorCaseRepository::activityOf).toList(),
                readList(rs.getString("audits")).stream()
                        .map(PostgresOperatorCaseRepository::auditOf).toList(),
                rs.getString("resolution") == null
                        ? null : resolutionOf(readMap(rs.getString("resolution"))),                instant(rs, "first_seen"),
                instant(rs, "ack_due"),
                instant(rs, "resolve_due"),
                rs.getLong("revision"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private List<Object> readList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST);
        } catch (Exception e) {
            throw new IllegalStateException("operator_case jsonb 数组列解析失败", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("operator_case jsonb 对象列解析失败", e);
        }
    }

    // ------------------------------------------------------------------ JSON 投影（Instant → ISO 字符串）

    private static Map<String, Object> toMap(OperatorCase.Activity activity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("at", activity.at().toString());
        map.put("actor", activity.actor());
        map.put("text", activity.text());
        return map;
    }

    private static OperatorCase.Activity activityOf(Object row) {
        Map<String, Object> map = asMap(row);
        return new OperatorCase.Activity(Instant.parse(text(map, "at")),
                text(map, "actor"), text(map, "text"));
    }

    private static Map<String, Object> toMap(OperatorCase.Audit audit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("at", audit.at().toString());
        map.put("actor", audit.actor());
        map.put("action", audit.action());
        map.put("revision", audit.revision());
        map.put("key", audit.key());
        return map;
    }

    private static OperatorCase.Audit auditOf(Object row) {
        Map<String, Object> map = asMap(row);
        return new OperatorCase.Audit(Instant.parse(text(map, "at")), text(map, "actor"),
                text(map, "action"), ((Number) map.get("revision")).longValue(), text(map, "key"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object row) {
        return (Map<String, Object>) row;
    }

    private static Map<String, Object> toMap(OperatorCase.Resolution resolution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", resolution.code());
        map.put("note", resolution.note());
        map.put("at", resolution.at().toString());
        return map;
    }

    private static OperatorCase.Resolution resolutionOf(Map<String, Object> map) {
        return new OperatorCase.Resolution(text(map, "code"), text(map, "note"),
                Instant.parse(text(map, "at")));
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("operator_case jsonb 序列化失败", e);
        }
    }

    private static String digestOrNull(Digest digest) {
        return digest == null ? null : digest.hex();
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}
