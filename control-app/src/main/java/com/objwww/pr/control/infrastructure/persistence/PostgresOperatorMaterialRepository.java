package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.OperatorMaterial;
import com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * incident_operator_material 的 Postgres 实现（MC31/32，V96）。append-only：
 * insert 唯一键冲突显式抛（uq(incident_id, revision) 竞态兜底）。
 */
public class PostgresOperatorMaterialRepository implements OperatorMaterialRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresOperatorMaterialRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(OperatorMaterial m) {
        jdbc.sql("""
                INSERT INTO incident_operator_material (
                    id, incident_id, run_id, operator, kind, source_ref, content,
                    base_revision, revision, admission, created_at
                ) VALUES (
                    :id, :incidentId, :runId, :operator, :kind, :sourceRef, :content,
                    :baseRevision, :revision, :admission, :createdAt
                )
                """)
                .param("id", m.id())
                .param("incidentId", m.incidentId())
                .param("runId", m.runId())
                .param("operator", m.operator())
                .param("kind", m.kind().name())
                .param("sourceRef", m.sourceRef())
                .param("content", m.content())
                .param("baseRevision", m.baseRevision())
                .param("revision", m.revision())
                .param("admission", m.admission().name())
                .param("createdAt", Timestamp.from(m.createdAt()))
                .update();
    }

    @Override
    public List<OperatorMaterial> findByIncident(UUID incidentId) {
        return jdbc.sql("""
                SELECT * FROM incident_operator_material
                 WHERE incident_id = :incidentId
                 ORDER BY revision
                """)
                .param("incidentId", incidentId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public int currentRevision(UUID incidentId) {
        // coalesce：空材料集时 max(revision) 为 NULL，single() 拒绝 null——基线即 0
        Long max = jdbc.sql("""
                SELECT coalesce(max(revision), 0) FROM incident_operator_material
                 WHERE incident_id = :incidentId
                """)
                .param("incidentId", incidentId)
                .query(Long.class)
                .single();
        return max.intValue();
    }

    @Override
    public List<OperatorMaterial> findAcceptedByRun(UUID runId) {
        return jdbc.sql("""
                SELECT * FROM incident_operator_material
                 WHERE run_id = :runId AND admission = 'ACCEPTED'
                 ORDER BY revision
                """)
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private OperatorMaterial mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new OperatorMaterial(
                rs.getObject("id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("operator"),
                OperatorMaterial.Kind.valueOf(rs.getString("kind")),
                rs.getString("source_ref"),
                rs.getString("content"),
                rs.getInt("base_revision"),
                rs.getInt("revision"),
                OperatorMaterial.Admission.valueOf(rs.getString("admission")),
                createdAt.toInstant());
    }
}
