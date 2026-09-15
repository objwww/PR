package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V14 rca_event 的 Postgres 实现（AM4 M4-10/11）。
 *
 * <p>seq 分配与事件插入在<b>同一短事务</b>：rca_run 行锁（M4-10 计数器锁语义）
 * 串行化同 run 并发追加；事务回滚 → seq 与事件一并回滚（无空洞）。幂等/冲突：
 * 查 (run_id, event_id)——同 digest 返回既有 seq（幂等，不触碰 run 行）；不同
 * digest 显式抛冲突（禁静默 no-op）。
 *
 * <p>BA-44（195 真 PG 实证）：V28 唯一约束按 E-17 并入分区键 created_at 后，
 * 同键并发双写的 DB 兜底消失（created_at 各异即不撞 UNIQUE，3 行同键事件实证）
 * ——串行化改为 rca_run 行锁 <b>先于</b> 幂等判定（FOR UPDATE 锁内重查），
 * check-then-insert 竞态窗关闭；锁内命中重放不 bump 计数器（无空洞）。
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
        // 快路径：已落账的同键事件直接重放/冲突，无竞争时不拿行锁
        Optional<Long> replayed = findReplaySeq(runId, draft.eventId(), digest);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        jdbc.sql("select id from rca_run where id = :run for update")
                .param("run", runId)
                .query((rs, n) -> rs.getString(1))   // uuid 列取文本即可：本查询只求行锁与存在性
                .optional()
                .orElseThrow(() -> new IllegalStateException("run 不存在，事件无法落账: " + runId));
        // 锁内重查：同 run 并发追加已在行锁上串行化，此处所见即终态
        replayed = findReplaySeq(runId, draft.eventId(), digest);
        if (replayed.isPresent()) {
            return replayed.get();  // 幂等重放不 bump 计数器（seq 无空洞）
        }
        return insert(runId, draft, digest);
    }

    /** 同 event_id 查重：同 digest = 幂等重放（既有 seq）；异 digest = 显式冲突 */
    private Optional<Long> findReplaySeq(UUID runId, UUID eventId, String digest) {
        return jdbc.sql("""
                select seq, payload_digest from rca_event
                 where run_id = :run and event_id = :eventId
                """)
                .param("run", runId).param("eventId", eventId)
                .query((rs, n) -> {
                    if (!rs.getString("payload_digest").equals(digest)) {
                        throw new IllegalStateException(
                                "事件冲突（同 event_id 不同 digest，禁静默 no-op）: run="
                                        + runId + " eventId=" + eventId);
                    }
                    return rs.getLong("seq");
                })
                .optional();
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
        // PA-A2：链写与 seq 分配同事务——run 行锁已串行化同 run 追加，前驱 event_hash
        // 此处所见即终态；legacy 尾（NULL）后首行以 'GENESIS' 起新验链段
        String prevHash = jdbc.sql("""
                select coalesce(event_hash, 'GENESIS') from rca_event
                 where run_id = :run and seq = :seq
                """)
                .param("run", runId).param("seq", seq - 1)
                .query((rs, n) -> rs.getString(1))
                .optional().orElse("GENESIS");
        String eventHash = Digest.sha256Of(prevHash + ":" + seq + ":"
                + draft.eventType() + ":" + digest).value();
        jdbc.sql("""
                insert into rca_event(id, run_id, seq, event_id, event_type, payload,
                    payload_digest, prev_hash, event_hash)
                values (:id, :run, :seq, :eventId, :type, CAST(:payload AS jsonb), :digest,
                    :prevHash, :eventHash)
                """)
                .param("id", UUID.randomUUID()).param("run", runId).param("seq", seq)
                .param("eventId", draft.eventId()).param("type", draft.eventType())
                .param("payload", draft.payloadJson()).param("digest", digest)
                .param("prevHash", prevHash).param("eventHash", eventHash)
                .update();
        return seq;
    }

    @Override
    public List<UUID> runIdsWithEvents() {
        return jdbc.sql("select distinct run_id from rca_event")
                .query((rs, n) -> rs.getObject("run_id", UUID.class))
                .list();
    }

    @Override
    public ChainReport verifyChain(UUID runId) {
        record Row(long seq, String prevHash, String eventHash, String type, String digest) {
        }
        List<Row> rows = jdbc.sql("""
                select seq, prev_hash, event_hash, event_type, payload_digest
                  from rca_event where run_id = :run order by seq
                """)
                .param("run", runId)
                .query((rs, n) -> new Row(rs.getLong("seq"), rs.getString("prev_hash"),
                        rs.getString("event_hash"), rs.getString("event_type"),
                        rs.getString("payload_digest")))
                .list();
        String expectedPrev = "GENESIS";
        long verified = 0;
        for (Row row : rows) {
            if (row.prevHash() == null || row.eventHash() == null) {
                // legacy 行（V112 前落账）：链段边界，跳过校验并重置段起点
                expectedPrev = "GENESIS";
                continue;
            }
            String recomputed = Digest.sha256Of(expectedPrev + ":" + row.seq() + ":"
                    + row.type() + ":" + row.digest()).value();
            if (!row.prevHash().equals(expectedPrev) || !row.eventHash().equals(recomputed)) {
                return new ChainReport(runId, rows.size(), verified, row.seq());
            }
            expectedPrev = row.eventHash();
            verified++;
        }
        return new ChainReport(runId, rows.size(), verified, -1);
    }
}
