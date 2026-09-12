package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill 候选仓储（EN-08，V97）：生命周期行 update 授权；幂等键 uq(source_digest,
 * name)——insert 撞键=false（S14 重放面），23505 竞态同语义收敛。
 */
public class PostgresSkillCandidateRepository implements SkillCandidateRepository {

    private final JdbcClient jdbc;

    public PostgresSkillCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(SkillCandidate candidate) {
        try {
            jdbc.sql("""
                    insert into rca_skill_candidate (id, name, source_run_id, source_digest,
                        verification_status, asset_digest, status, failure_reason, proposed_by,
                        activated_by, activated_at, retired_by, retired_at, retire_reason,
                        created_at, updated_at)
                    values (:id, :name, :source_run_id, :source_digest, :verification_status,
                        :asset_digest, :status, :failure_reason, :proposed_by, :activated_by,
                        :activated_at, :retired_by, :retired_at, :retire_reason,
                        :created_at, :updated_at)
                    """)
                    .param("id", candidate.id())
                    .param("name", candidate.name())
                    .param("source_run_id", candidate.sourceRunId())
                    .param("source_digest", candidate.sourceDigest())
                    .param("verification_status", candidate.verificationStatus())
                    .param("asset_digest", candidate.assetDigest())
                    .param("status", candidate.status())
                    .param("failure_reason", candidate.failureReason())
                    .param("proposed_by", candidate.proposedBy())
                    .param("activated_by", candidate.activatedBy())
                    .param("activated_at", timestampOf(candidate.activatedAt()))
                    .param("retired_by", candidate.retiredBy())
                    .param("retired_at", timestampOf(candidate.retiredAt()))
                    .param("retire_reason", candidate.retireReason())
                    .param("created_at", Timestamp.from(candidate.createdAt()))
                    .param("updated_at", Timestamp.from(candidate.updatedAt()))
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<SkillCandidate> findById(UUID id) {
        return jdbc.sql("""
                        select id, name, source_run_id, source_digest, verification_status,
                               asset_digest, status, failure_reason, proposed_by, activated_by,
                               activated_at, retired_by, retired_at, retire_reason,
                               created_at, updated_at
                          from rca_skill_candidate where id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> rowOf(rs))
                .optional();
    }

    @Override
    public Optional<SkillCandidate> findBySource(String sourceDigest, String name) {
        return jdbc.sql("""
                        select id, name, source_run_id, source_digest, verification_status,
                               asset_digest, status, failure_reason, proposed_by, activated_by,
                               activated_at, retired_by, retired_at, retire_reason,
                               created_at, updated_at
                          from rca_skill_candidate where source_digest = :source and name = :name
                        """)
                .param("source", sourceDigest)
                .param("name", name)
                .query((rs, n) -> rowOf(rs))
                .optional();
    }

    @Override
    public void update(SkillCandidate candidate) {
        jdbc.sql("""
                update rca_skill_candidate set asset_digest = :asset_digest, status = :status,
                    failure_reason = :failure_reason, activated_by = :activated_by,
                    activated_at = :activated_at, retired_by = :retired_by,
                    retired_at = :retired_at, retire_reason = :retire_reason,
                    updated_at = :updated_at
                  where id = :id
                """)
                .param("asset_digest", candidate.assetDigest())
                .param("status", candidate.status())
                .param("failure_reason", candidate.failureReason())
                .param("activated_by", candidate.activatedBy())
                .param("activated_at", timestampOf(candidate.activatedAt()))
                .param("retired_by", candidate.retiredBy())
                .param("retired_at", timestampOf(candidate.retiredAt()))
                .param("retire_reason", candidate.retireReason())
                .param("updated_at", Timestamp.from(candidate.updatedAt()))
                .param("id", candidate.id())
                .update();
    }

    @Override
    public List<SkillCandidate> listByStatus(String status) {
        return jdbc.sql("""
                        select id, name, source_run_id, source_digest, verification_status,
                               asset_digest, status, failure_reason, proposed_by, activated_by,
                               activated_at, retired_by, retired_at, retire_reason,
                               created_at, updated_at
                          from rca_skill_candidate where status = :status order by name
                        """)
                .param("status", status)
                .query((rs, n) -> rowOf(rs))
                .list();
    }

    private static SkillCandidate rowOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SkillCandidate(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("source_run_id", UUID.class),
                rs.getString("source_digest"),
                rs.getString("verification_status"),
                rs.getString("asset_digest"),
                rs.getString("status"),
                rs.getString("failure_reason"),
                rs.getString("proposed_by"),
                rs.getString("activated_by"),
                instantOf(rs.getTimestamp("activated_at")),
                rs.getString("retired_by"),
                instantOf(rs.getTimestamp("retired_at")),
                rs.getString("retire_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestampOf(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static java.time.Instant instantOf(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
