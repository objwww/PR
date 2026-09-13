package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.release.domain.model.SkillRunBinding;
import com.objwww.pr.control.release.domain.repository.SkillRunBindingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill 每 Run 绑定仓储 PG 实现（CL-05，V100）：insert on conflict do nothing
 * 撞主键=false（单写者语义，23505 竞态同收敛）；行落库后无 update 面。
 */
public class PostgresSkillRunBindingRepository implements SkillRunBindingRepository {

    private final JdbcClient jdbc;

    public PostgresSkillRunBindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(SkillRunBinding binding) {
        try {
            jdbc.sql("""
                    insert into rca_run_skill_binding (run_id, role_id, config_epoch,
                        selection_status, asset_digest, release_digest, selector_version,
                        source_command_id, created_at)
                    values (:run_id, :role_id, :config_epoch, :selection_status,
                        :asset_digest, :release_digest, :selector_version,
                        :source_command_id, :created_at)
                    """)
                    .param("run_id", binding.runId())
                    .param("role_id", binding.roleId())
                    .param("config_epoch", binding.configEpoch())
                    .param("selection_status", binding.selectionStatus())
                    .param("asset_digest", binding.assetDigest())
                    .param("release_digest", binding.releaseDigest())
                    .param("selector_version", binding.selectorVersion())
                    .param("source_command_id", binding.sourceCommandId())
                    .param("created_at", Timestamp.from(binding.createdAt()))
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<SkillRunBinding> find(UUID runId, String roleId, long configEpoch) {
        return jdbc.sql("""
                        select run_id, role_id, config_epoch, selection_status, asset_digest,
                               release_digest, selector_version, source_command_id, created_at
                          from rca_run_skill_binding
                         where run_id = :run_id and role_id = :role_id
                           and config_epoch = :config_epoch
                        """)
                .param("run_id", runId)
                .param("role_id", roleId)
                .param("config_epoch", configEpoch)
                .query((rs, n) -> new SkillRunBinding(
                        rs.getObject("run_id", UUID.class),
                        rs.getString("role_id"),
                        rs.getLong("config_epoch"),
                        rs.getString("selection_status"),
                        rs.getString("asset_digest"),
                        rs.getString("release_digest"),
                        rs.getString("selector_version"),
                        rs.getObject("source_command_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
