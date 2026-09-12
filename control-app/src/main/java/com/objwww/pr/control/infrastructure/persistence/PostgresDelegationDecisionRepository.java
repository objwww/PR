package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_delegation_decision 的 Postgres 实现（R7-X4/X11，V47）。行=既成事实：
 * insert 唯一键冲突显式抛（uq(run,gap) 去重面的调用方幂等短路依据）；
 * attachChild 单列限定回填（WHERE status='APPROVED' AND child_task_id IS NULL）。
 */
public class PostgresDelegationDecisionRepository implements DelegationDecisionRepository {

    private final JdbcClient jdbc;

    public PostgresDelegationDecisionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(DelegationDecision d) {
        jdbc.sql("""
                INSERT INTO rca_delegation_decision (
                    id, run_id, primary_task_id, round_id, seq,
                    gap_id, role_id, role_version, question, -- question 仅台账审计，执行面不消费（R3 路线B）
                    status, reject_reason, child_task_id, created_at
                ) VALUES (
                    :id, :runId, :primaryTaskId, :roundId, :seq,
                    :gapId, :roleId, :roleVersion, :question,
                    :status, :rejectReason, :childTaskId, :createdAt
                )
                """)
                .param("id", d.id())
                .param("runId", d.runId())
                .param("primaryTaskId", d.primaryTaskId())
                .param("roundId", d.roundId())
                .param("seq", d.seq())
                .param("gapId", d.gapId())
                .param("roleId", d.roleId())
                .param("roleVersion", d.roleVersion())
                .param("question", d.question())
                .param("status", d.status().name())
                .param("rejectReason", d.rejectReason())
                .param("childTaskId", d.childTaskId())
                .param("createdAt", Timestamp.from(d.createdAt()))
                .update();
    }

    @Override
    public Optional<DelegationDecision> findById(UUID id) {
        List<DelegationDecision> rows = jdbc.sql("""
                SELECT * FROM rca_delegation_decision WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<DelegationDecision> findByRunAndGap(UUID runId, String gapId) {
        List<DelegationDecision> rows = jdbc.sql("""
                SELECT * FROM rca_delegation_decision
                 WHERE run_id = :runId AND gap_id = :gapId
                """)
                .param("runId", runId)
                .param("gapId", gapId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DelegationDecision> findByRunAndPrimaryTask(UUID runId, UUID primaryTaskId) {
        return jdbc.sql("""
                SELECT * FROM rca_delegation_decision
                 WHERE run_id = :runId AND primary_task_id = :taskId
                 ORDER BY round_id, seq
                """)
                .param("runId", runId)
                .param("taskId", primaryTaskId)
                .query(this::mapRow)
                .list();
    }

    private DelegationDecision mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new DelegationDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("primary_task_id", UUID.class),
                rs.getInt("round_id"),
                rs.getInt("seq"),
                rs.getString("gap_id"),
                rs.getString("role_id"),
                rs.getString("role_version"),
                rs.getString("question"),
                DelegationDecision.Status.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getObject("child_task_id", UUID.class),
                createdAt.toInstant());
    }
}
