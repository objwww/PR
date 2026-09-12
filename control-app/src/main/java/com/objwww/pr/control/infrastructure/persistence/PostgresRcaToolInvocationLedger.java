package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.UUID;

/**
 * V15 工具调用账本的 Postgres 实现（AM4 M4-18）。每方法自含短事务；终态迁移 =
 * CAS（WHERE state='PENDING'），首回执生效、败者返回 false（终态不可改写）。
 */
public class PostgresRcaToolInvocationLedger implements RcaToolInvocationLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresRcaToolInvocationLedger(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public void open(InvocationIdentity identity) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                insert into rca_tool_invocation(id, run_id, task_id, attempt_id, call_seq,
                    tool_name, tool_version, action_digest, action_seq, state)
                values (:id, :run, :task, :attempt, :seq, :tool, :version, :digest, :seq,
                    'PENDING')
                """)
                .param("id", identity.operationId()).param("run", identity.runId())
                .param("task", identity.taskId()).param("attempt", identity.attemptId())
                .param("seq", identity.callSeq()).param("tool", identity.toolName())
                .param("version", identity.toolVersion())
                .param("digest", identity.actionDigest())
                .update());
    }

    @Override
    public boolean succeed(UUID operationId) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                        update rca_tool_invocation
                           set state = 'SUCCESS', settled_at = now()
                         where id = :id and state = 'PENDING'
                        """)
                .param("id", operationId)
                .update());
        return updated != null && updated == 1;
    }

    @Override
    public boolean fail(UUID operationId, ToolInvocationState terminal,
            ToolReasonCode reasonCode) {
        if (terminal != ToolInvocationState.FAILED && terminal != ToolInvocationState.UNKNOWN) {
            throw new IllegalArgumentException(
                    "fail 只接受 FAILED/UNKNOWN，实际: " + terminal);
        }
        Integer updated = tx.execute(status -> jdbc.sql("""
                        update rca_tool_invocation
                           set state = :state, reason_code = :reason, settled_at = now()
                         where id = :id and state = 'PENDING'
                        """)
                .param("state", terminal.name()).param("reason", reasonCode.name())
                .param("id", operationId)
                .update());
        return updated != null && updated == 1;
    }

    /** EX-A4a（F16）：悬挂 PENDING 单语句回收 → UNKNOWN/TRANSPORT_UNKNOWN（CAS 语义在 WHERE state） */
    @Override
    public int reclaimPendingOlderThan(java.time.Instant cutoff) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                        update rca_tool_invocation
                           set state = 'UNKNOWN', reason_code = 'TRANSPORT_UNKNOWN',
                               settled_at = now()
                         where state = 'PENDING' and started_at < :cutoff
                        """)
                .param("cutoff", java.sql.Timestamp.from(cutoff))
                .update());
        return updated == null ? 0 : updated;
    }

    /** EX-A3（F09）：结果引用随账落档——CAS 锚 PENDING（succeed 前调用） */
    @Override
    public boolean markResultRef(UUID operationId, UUID evidenceId) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                        update rca_tool_invocation
                           set result_ref = :ref
                         where id = :id and state = 'PENDING'
                        """)
                .param("ref", evidenceId).param("id", operationId)
                .update());
        return updated != null && updated == 1;
    }

    /** EX-A3（F08）：恢复读——某 run 某任务账本行（call_seq 序），四阶段分诊输入 */
    @Override
    public java.util.List<InvocationRecovery> findRecoveryByTask(UUID runId, UUID taskId) {
        return tx.execute(status -> jdbc.sql("""
                        SELECT id, call_seq, attempt_id, action_digest, state, result_ref
                          FROM rca_tool_invocation
                         WHERE run_id = :run AND task_id = :task
                         ORDER BY call_seq, id
                        """)
                .param("run", runId).param("task", taskId)
                .query((rs, n) -> new InvocationRecovery(
                        rs.getObject("id", UUID.class),
                        rs.getLong("call_seq"),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getString("action_digest"),
                        ToolInvocationState.valueOf(rs.getString("state")),
                        rs.getObject("result_ref", UUID.class)))
                .list());
    }

    /** MC24 同现场复用读面——run 维度已成功且带结果引用的账本行（call_seq 序） */
    @Override
    public java.util.List<InvocationRecovery> findSuccessfulByRun(UUID runId) {
        return tx.execute(status -> jdbc.sql("""
                        SELECT id, call_seq, attempt_id, action_digest, state, result_ref
                          FROM rca_tool_invocation
                         WHERE run_id = :run AND state = 'SUCCESS'
                           AND result_ref IS NOT NULL
                         ORDER BY call_seq, id
                        """)
                .param("run", runId)
                .query((rs, n) -> new InvocationRecovery(
                        rs.getObject("id", UUID.class),
                        rs.getLong("call_seq"),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getString("action_digest"),
                        ToolInvocationState.valueOf(rs.getString("state")),
                        rs.getObject("result_ref", UUID.class)))
                .list());
    }
}
