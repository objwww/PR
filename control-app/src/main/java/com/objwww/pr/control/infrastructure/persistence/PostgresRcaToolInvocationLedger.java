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
                    tool_name, tool_version, action_digest, state)
                values (:id, :run, :task, :attempt, :seq, :tool, :version, :digest, 'PENDING')
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
}
