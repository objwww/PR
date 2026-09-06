package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.UUID;

/**
 * V14 rca_event 的 Postgres 实现（AM4 M4-10/11）。
 *
 * <p>seq 分配与事件插入在<b>同一短事务</b>：{@code UPDATE rca_run SET
 * last_event_seq = last_event_seq + 1 ... RETURNING} 的行锁串行化同 run 并发追加；
 * 事务回滚 → seq 与事件一并回滚（无空洞）。幂等/冲突：先查 (run_id, event_id)
 * ——同 digest 返回既有 seq（幂等，不触碰 run 行）；不同 digest 显式抛冲突
 * （禁静默 no-op）。并发同键双写时败者经 UNIQUE 约束回滚（seq 一并回退，无空洞）。
 */
public class PostgresRcaEventAppender implements RcaEventAppender {

    private final JdbcClient jdbc;
    private final TransactionOperations joinTx;
    private final TransactionOperations independentTx;

    public PostgresRcaEventAppender(JdbcClient jdbc, TransactionOperations joinTx,
            TransactionOperations independentTx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.joinTx = Objects.requireNonNull(joinTx);
        this.independentTx = Objects.requireNonNull(independentTx);
    }

    @Override
    public long append(UUID runId, EventDraft draft) {
        return joinTx.execute(status -> doAppend(runId, draft));
    }

    @Override
    public long appendIndependent(UUID runId, EventDraft draft) {
        return independentTx.execute(status -> doAppend(runId, draft));
    }

    private long doAppend(UUID runId, EventDraft draft) {
        String digest = Digest.sha256Of(draft.payloadJson()).value();
        return jdbc.sql("""
                select seq, payload_digest from rca_event
                 where run_id = :run and event_id = :eventId
                """)
                .param("run", runId).param("eventId", draft.eventId())
                .query((rs, n) -> {
                    if (!rs.getString("payload_digest").equals(digest)) {
                        throw new IllegalStateException(
                                "事件冲突（同 event_id 不同 digest，禁静默 no-op）: run="
                                        + runId + " eventId=" + draft.eventId());
                    }
                    return rs.getLong("seq"); // 同 event_id + 同 digest = 幂等重放
                })
                .optional()
                .orElseGet(() -> insert(runId, draft, digest));
    }

    private long insert(UUID runId, EventDraft draft, String digest) {
        Long seq = jdbc.sql("""
                update rca_run set last_event_seq = last_event_seq + 1, updated_at = now()
                 where id = :run
                returning last_event_seq
                """)
                .param("run", runId)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("run 不存在，事件无法落账: " + runId));
        jdbc.sql("""
                insert into rca_event(id, run_id, seq, event_id, event_type, payload,
                    payload_digest)
                values (:id, :run, :seq, :eventId, :type, CAST(:payload AS jsonb), :digest)
                """)
                .param("id", UUID.randomUUID()).param("run", runId).param("seq", seq)
                .param("eventId", draft.eventId()).param("type", draft.eventType())
                .param("payload", draft.payloadJson()).param("digest", digest)
                .update();
        return seq;
    }
}
