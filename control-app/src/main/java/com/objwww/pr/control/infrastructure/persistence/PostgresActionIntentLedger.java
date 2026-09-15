package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.ActionIntentLedger;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ActionIntent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;

/**
 * V114 action_intent 的 Postgres 实现（PB-B1）：意图行 INSERT 与意图事件 append
 * 在<b>同一 REQUIRES_NEW 短事务</b>——行是投影，事件是授权事实读路径（B 组不变量），
 * 二者要么同在要么同无（A12 路线 B 不变式的意图面）。
 *
 * <p>事件 append 走 {@link RcaEventAppender#append}（REQUIRED，加入本短事务），
 * run 行锁串行化同 run 追加的既有语义不变。
 */
public class PostgresActionIntentLedger implements ActionIntentLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations independentTx;
    private final RcaEventAppender events;

    public PostgresActionIntentLedger(JdbcClient jdbc, TransactionOperations independentTx,
            RcaEventAppender events) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.independentTx = Objects.requireNonNull(independentTx);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public long record(ActionIntent intent, RcaEventAppender.EventDraft intentEvent) {
        return independentTx.execute(status -> {
            jdbc.sql("""
                    insert into action_intent(intent_id, run_id, task_id, attempt_id,
                        call_seq, tool_name, tool_version, action_id, action_digest,
                        risk, requested_resource_key, status, args_json)
                    values (:intentId, :runId, :taskId, :attemptId, :callSeq,
                        :toolName, :toolVersion, :actionId, :actionDigest,
                        :risk, :requestedResourceKey, 'OPEN', CAST(:argsJson AS jsonb))
                    """)
                    .param("intentId", intent.intentId())
                    .param("runId", intent.runId())
                    .param("taskId", intent.taskId())
                    .param("attemptId", intent.attemptId())
                    .param("callSeq", intent.callSeq())
                    .param("toolName", intent.toolName())
                    .param("toolVersion", intent.toolVersion())
                    .param("actionId", intent.actionId())
                    .param("actionDigest", intent.actionDigest())
                    .param("risk", intent.risk().name())
                    .param("requestedResourceKey", intent.requestedResourceKey())
                    .param("argsJson", intent.argsJson())
                    .update();
            return events.append(intent.runId(), intentEvent);
        });
    }
}
