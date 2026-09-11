package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.classification.IncidentCategory;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IncidentCategoryRepository} 的 Postgres 实现（UX-01，V82 面）。
 *
 * <p>生效面 category/category_source 是 STORED 生成列，本类 SQL 从不写它——
 * 一致性由 DB 保证。CAS 写带 override_revision 谓词；审计表只 INSERT/SELECT。
 */
public class PostgresIncidentCategoryRepository implements IncidentCategoryRepository {

    private final JdbcClient jdbc;

    public PostgresIncidentCategoryRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void applyRuleClassification(UUID incidentId, IncidentCategory category,
                                        String ruleId, String ruleVersion,
                                        Instant classifiedAt) {
        // 只写 rule_* 四列；override_* 与 updated_at（聚合态时刻）均不触碰
        jdbc.sql("""
                UPDATE incident SET
                    rule_category = :category,
                    category_rule_id = :ruleId,
                    category_rule_version = :ruleVersion,
                    category_classified_at = :classifiedAt
                 WHERE id = :id
                """)
                .param("category", category.name())
                .param("ruleId", ruleId)
                .param("ruleVersion", ruleVersion)
                .param("classifiedAt", Timestamp.from(classifiedAt))
                .param("id", incidentId)
                .update();
    }

    @Override
    public Optional<CategoryState> lockState(UUID incidentId) {
        List<CategoryState> rows = jdbc.sql("""
                SELECT rule_category, override_category, category, override_revision
                  FROM incident WHERE id = :id FOR UPDATE
                """)
                .param("id", incidentId)
                .query((rs, i) -> new CategoryState(rs.getString("rule_category"),
                        rs.getString("override_category"), rs.getString("category"),
                        rs.getInt("override_revision")))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean setOverride(UUID incidentId, IncidentCategory category, String actor,
                               String reason, Instant at, int expectedRevision) {
        return jdbc.sql("""
                UPDATE incident SET
                    override_category = :category,
                    override_actor = :actor,
                    override_reason = :reason,
                    override_at = :at,
                    override_revision = override_revision + 1
                 WHERE id = :id AND override_revision = :expected
                """)
                .param("category", category.name())
                .param("actor", actor)
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .param("id", incidentId)
                .param("expected", expectedRevision)
                .update() == 1;
    }

    @Override
    public boolean clearOverride(UUID incidentId, int expectedRevision) {
        return jdbc.sql("""
                UPDATE incident SET
                    override_category = null,
                    override_actor = null,
                    override_reason = null,
                    override_at = null,
                    override_revision = override_revision + 1
                 WHERE id = :id AND override_revision = :expected
                """)
                .param("id", incidentId)
                .param("expected", expectedRevision)
                .update() == 1;
    }

    @Override
    public void appendAudit(OverrideAuditRow row) {
        jdbc.sql("""
                INSERT INTO incident_category_override (
                    id, incident_id, action, from_category, to_category,
                    actor, reason, expected_revision, result_revision,
                    idempotency_key, created_at
                ) VALUES (
                    :id, :incidentId, :action, :fromCategory, :toCategory,
                    :actor, :reason, :expectedRevision, :resultRevision,
                    :idempotencyKey, :createdAt
                )
                """)
                .param("id", row.id())
                .param("incidentId", row.incidentId())
                .param("action", row.action())
                .param("fromCategory", row.fromCategory())
                .param("toCategory", row.toCategory())
                .param("actor", row.actor())
                .param("reason", row.reason())
                .param("expectedRevision", row.expectedRevision())
                .param("resultRevision", row.resultRevision())
                .param("idempotencyKey", row.idempotencyKey())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .update();
    }

    @Override
    public Optional<OverrideAuditRow> findAuditByIdempotencyKey(UUID incidentId,
                                                                String idempotencyKey) {
        List<OverrideAuditRow> rows = jdbc.sql("""
                SELECT id, incident_id, action, from_category, to_category,
                       actor, reason, expected_revision, result_revision,
                       idempotency_key, created_at
                  FROM incident_category_override
                 WHERE incident_id = :incidentId AND idempotency_key = :key
                """)
                .param("incidentId", incidentId)
                .param("key", idempotencyKey)
                .query(this::mapAudit)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private OverrideAuditRow mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new OverrideAuditRow(
                rs.getObject("id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                rs.getString("action"),
                rs.getString("from_category"),
                rs.getString("to_category"),
                rs.getString("actor"),
                rs.getString("reason"),
                rs.getInt("expected_revision"),
                rs.getInt("result_revision"),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant());
    }
}
