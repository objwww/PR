package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.ops.domain.model.ActionAssessment;
import com.objwww.pr.control.ops.domain.repository.ActionAssessmentPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_action_assessment 的 Postgres 实现（OP-03，V105）：唯一键冲突回读既有行
 * （重入幂等）；只增不改——无任何 UPDATE/DELETE 路径。
 */
public class PostgresActionAssessment implements ActionAssessmentPort {

    private static final TypeReference<List<String>> REFS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresActionAssessment(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public ActionAssessment insertIfAbsent(ActionAssessment candidate) {
        try {
            jdbc.sql("""
                    insert into rca_action_assessment (id, run_id, task_id,
                        logical_action_key, assessor_version, evidence_snapshot_digest,
                        physical_attempts, new_observation_count, gap_resolution_refs,
                        report_citation_count, classification, confidence_kind,
                        computed_at)
                    values (:id, :runId, :taskId, :logicalActionKey, :assessorVersion,
                        :snapshotDigest, :physicalAttempts, :newObservationCount,
                        cast(:gapResolutionRefs as jsonb), :reportCitationCount,
                        :classification, :confidenceKind, :computedAt)
                    """)
                    .param("id", candidate.id())
                    .param("runId", candidate.runId())
                    .param("taskId", candidate.taskId())
                    .param("logicalActionKey", candidate.logicalActionKey())
                    .param("assessorVersion", candidate.assessorVersion())
                    .param("snapshotDigest", candidate.evidenceSnapshotDigest())
                    .param("physicalAttempts", candidate.physicalAttempts())
                    .param("newObservationCount", candidate.newObservationCount())
                    .param("gapResolutionRefs", jsonOf(candidate.gapResolutionRefs()))
                    .param("reportCitationCount", candidate.reportCitationCount())
                    .param("classification", candidate.classification())
                    .param("confidenceKind", candidate.confidenceKind())
                    .param("computedAt", Timestamp.from(candidate.computedAt()))
                    .update();
            return candidate;
        } catch (DuplicateKeyException e) {
            return jdbc.sql("""
                            select id, run_id, task_id, logical_action_key, assessor_version,
                                evidence_snapshot_digest, physical_attempts,
                                new_observation_count, gap_resolution_refs,
                                report_citation_count, classification, confidence_kind,
                                computed_at
                            from rca_action_assessment
                            where run_id = :runId and logical_action_key = :key
                                and assessor_version = :version
                                and evidence_snapshot_digest = :snapshot
                            """)
                    .param("runId", candidate.runId())
                    .param("key", candidate.logicalActionKey())
                    .param("version", candidate.assessorVersion())
                    .param("snapshot", candidate.evidenceSnapshotDigest())
                    .query(this::mapRow)
                    .single();
        }
    }

    @Override
    public List<ActionAssessment> findByRun(UUID runId) {
        return jdbc.sql("""
                        select id, run_id, task_id, logical_action_key, assessor_version,
                            evidence_snapshot_digest, physical_attempts,
                            new_observation_count, gap_resolution_refs,
                            report_citation_count, classification, confidence_kind,
                            computed_at
                        from rca_action_assessment
                        where run_id = :runId order by logical_action_key
                        """)
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private ActionAssessment mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        List<String> refs;
        try {
            String raw = rs.getString("gap_resolution_refs");
            refs = raw == null ? List.of() : mapper.readValue(raw, REFS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gap_resolution_refs 解析失败", e);
        }
        return new ActionAssessment(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("logical_action_key"),
                rs.getString("assessor_version"),
                rs.getString("evidence_snapshot_digest"),
                rs.getInt("physical_attempts"),
                rs.getInt("new_observation_count"),
                refs,
                rs.getInt("report_citation_count"),
                rs.getString("classification"),
                rs.getString("confidence_kind"),
                rs.getTimestamp("computed_at").toInstant());
    }

    private String jsonOf(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(refs);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("gap_resolution_refs 序列化失败", e);
        }
    }
}
