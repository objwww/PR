package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * scheduler_slot 的 Postgres 实现（固定槽位；原子抢占 + epoch 栅栏 + 过期回收，
 * 评审 #6——与 task 领取同一短事务，INV-AM1-7）。
 */
public class PostgresSchedulerSlotRepository implements SchedulerSlotRepository {

    private static final String ACQUIRE_SQL = """
            UPDATE scheduler_slot SET
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_epoch = lease_epoch + 1,
                task_id = :taskId,
                updated_at = :now
            WHERE (scope, slot_no) = (
                SELECT scope, slot_no FROM scheduler_slot
                 WHERE scope = :scope AND (lease_until IS NULL OR lease_until <= :now)
                 FOR UPDATE SKIP LOCKED LIMIT 1
            )
            RETURNING slot_no, lease_epoch
            """;

    private final JdbcClient jdbc;

    public PostgresSchedulerSlotRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<AcquiredSlot> tryAcquire(String scope, String owner, UUID taskId,
                                             Instant now, Duration lease) {
        record Row(int slotNo, long leaseEpoch) {
        }
        List<Row> slots = jdbc.sql(ACQUIRE_SQL)
                .param("owner", owner)
                .param("leaseUntil", Timestamp.from(now.plus(lease)))
                .param("taskId", taskId)
                .param("now", Timestamp.from(now))
                .param("scope", scope)
                .query((rs, n) -> new Row(rs.getInt("slot_no"), rs.getLong("lease_epoch")))
                .list();
        return slots.isEmpty() ? Optional.empty()
                : Optional.of(new AcquiredSlot(slots.get(0).slotNo(), slots.get(0).leaseEpoch()));
    }

    @Override
    public boolean release(String scope, int slotNo, String owner, long leaseEpoch) {
        return jdbc.sql("""
                UPDATE scheduler_slot SET lease_owner = NULL, lease_until = NULL, task_id = NULL
                 WHERE scope = :scope AND slot_no = :slotNo
                   AND lease_owner = :owner AND lease_epoch = :epoch
                """)
                .param("scope", scope).param("slotNo", slotNo)
                .param("owner", owner).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public void heartbeat(String scope, int slotNo, String owner, long leaseEpoch,
                          Instant now, Duration extend) {
        jdbc.sql("""
                UPDATE scheduler_slot SET lease_until = :leaseUntil, updated_at = :now
                 WHERE scope = :scope AND slot_no = :slotNo
                   AND lease_owner = :owner AND lease_epoch = :epoch
                """)
                .param("leaseUntil", Timestamp.from(now.plus(extend)))
                .param("now", Timestamp.from(now))
                .param("scope", scope).param("slotNo", slotNo)
                .param("owner", owner).param("epoch", leaseEpoch)
                .update();
    }

    @Override
    public long reclaimExpired(Instant now) {
        return jdbc.sql("""
                UPDATE scheduler_slot SET lease_owner = NULL, lease_until = NULL, task_id = NULL
                 WHERE lease_owner IS NOT NULL AND lease_until < :now
                """)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public List<Integer> occupiedSlots(String scope) {
        return jdbc.sql("SELECT slot_no FROM scheduler_slot"
                        + " WHERE scope = :scope AND lease_owner IS NOT NULL ORDER BY slot_no")
                .param("scope", scope)
                .query(Integer.class)
                .list();
    }

    @Override
    public int totalSlots(String scope) {
        return jdbc.sql("SELECT count(*) FROM scheduler_slot WHERE scope = :scope")
                .param("scope", scope)
                .query(Integer.class).single();
    }
}
