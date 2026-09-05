package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.CompensationEvent;
import com.objwww.pr.arena.domain.model.OutboxState;
import com.objwww.pr.arena.domain.model.ResourceType;
import com.objwww.pr.arena.domain.repository.CompensationOutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * oa_compensation_outbox 仓储。claim = SKIP LOCKED + CAS（M2-05）；
 * 终态/重试迁移全部带 epoch 栅栏（C-4）。payload 序列化为紧凑 JSON。
 */
public class PostgresCompensationOutboxRepository implements CompensationOutboxRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbc;

    public PostgresCompensationOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(CompensationEvent event) {
        jdbc.sql("""
                INSERT INTO arena.oa_compensation_outbox(id,order_id,event_type,payload,state,
                    attempt_count,available_at,created_at,updated_at)
                VALUES (:id,:orderId,'RESOURCE_REFUND',:payload::jsonb,'PENDING',0,now(),now(),now())
                """).param("id", event.id()).param("orderId", event.orderId())
                .param("payload", serializePlan(event.plan()))
                .update();
    }

    @Override
    public long countClaimable() {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_compensation_outbox
                WHERE state IN ('PENDING','RETRY_WAIT') AND available_at <= now()
                """).query(Long.class).single();
    }

    @Override
    public Optional<Claimed> claimNext(String owner, Duration lease) {
        return jdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state='CLAIMED', lease_owner=:owner,
                    lease_until=now()+make_interval(secs => :lease),
                    lease_epoch=lease_epoch+1, updated_at=now()
                WHERE id IN (
                    SELECT id FROM arena.oa_compensation_outbox
                    WHERE state IN ('PENDING','RETRY_WAIT') AND available_at <= now()
                    ORDER BY available_at, created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, order_id, payload, lease_epoch
                """).param("owner", owner).param("lease", lease.toSeconds())
                .query((rs, n) -> {
                    UUID id = UUID.fromString(rs.getString("id"));
                    UUID orderId = UUID.fromString(rs.getString("order_id"));
                    List<CompensationEvent.PlanEntry> plan = parsePlan(rs.getString("payload"));
                    long epoch = rs.getLong("lease_epoch");
                    return new Claimed(new CompensationEvent(id, orderId, plan, OutboxState.CLAIMED),
                            owner, epoch);
                })
                .optional();
    }

    @Override
    public int reapExpiredLeases() {
        return jdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state='PENDING', lease_owner=null, lease_until=null,
                    lease_epoch=lease_epoch+1, updated_at=now()
                WHERE state IN ('CLAIMED','EXECUTING') AND lease_until < now()
                """).update();
    }

    @Override
    public boolean casState(UUID id, OutboxState from, OutboxState to, long leaseEpoch) {
        return jdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state=:to, updated_at=now()
                WHERE id=:id AND state=:from AND lease_epoch=:epoch
                """).param("to", to.name()).param("id", id).param("from", from.name())
                .param("epoch", leaseEpoch).update() == 1;
    }

    @Override
    public boolean casTerminal(UUID id, OutboxState terminal, long leaseEpoch) {
        return jdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state=:to, finished_at=now(), lease_owner=null, lease_until=null, updated_at=now()
                WHERE id=:id AND state IN ('CLAIMED','EXECUTING') AND lease_epoch=:epoch
                """).param("to", terminal.name()).param("id", id)
                .param("epoch", leaseEpoch).update() == 1;
    }

    @Override
    public boolean casRetry(UUID id, long leaseEpoch, Duration backoff, int maxAttempts) {
        int updated = jdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state=CASE WHEN attempt_count+1 >= max_attempts THEN 'DEAD'
                               ELSE 'RETRY_WAIT' END,
                    attempt_count=attempt_count+1,
                    finished_at=CASE WHEN attempt_count+1 >= max_attempts THEN now() END,
                    lease_owner=null, lease_until=null,
                    available_at=now()+make_interval(secs => :backoff), updated_at=now()
                WHERE id=:id AND state='CLAIMED' AND lease_epoch=:epoch
                """).param("backoff", backoff.toSeconds()).param("id", id)
                .param("epoch", leaseEpoch).update();
        return updated == 1;
    }

    @Override
    public List<CompensationEvent> findByOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id,order_id,payload,state FROM arena.oa_compensation_outbox
                        WHERE order_id=:id ORDER BY created_at
                        """).param("id", orderId)
                .query((rs, n) -> new CompensationEvent(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("order_id")),
                        parsePlan(rs.getString("payload")),
                        OutboxState.valueOf(rs.getString("state"))))
                .list();
    }

    private String serializePlan(List<CompensationEvent.PlanEntry> plan) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (CompensationEvent.PlanEntry e : plan) {
            raw.add(Map.of("resourceType", e.resourceType().name(),
                    "deductionSeq", e.deductionSeq(), "quantity", e.quantity()));
        }
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("补偿计划序列化失败", e);
        }
    }

    private List<CompensationEvent.PlanEntry> parsePlan(String json) {
        try {
            List<Map<String, Object>> raw =
                    MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
                    });
            return raw.stream()
                    .map(m -> new CompensationEvent.PlanEntry(
                            ResourceType.valueOf((String) m.get("resourceType")),
                            ((Number) m.get("deductionSeq")).intValue(),
                            ((Number) m.get("quantity")).intValue()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("补偿计划反序列化失败", e);
        }
    }
}
