package com.objwww.pr.arena.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-05 补偿 outbox 契约 IT（V2）：
 * <ul>
 *   <li>SKIP LOCKED 双 worker 不重复领取（并发 IT）；</li>
 *   <li>租约过期回收可重领；</li>
 *   <li>旧 epoch 提交终态被拒（CAS 栅栏，0 行受影响）；</li>
 *   <li>EXPLAIN 命中 claim 索引（查询计划走 ix_outbox_claim）。</li>
 * </ul>
 */
class ArenaV2OutboxMigrationIT extends ArenaPostgresITBase {

    private static final String CLAIM_SQL = """
            SELECT id FROM arena.oa_compensation_outbox
            WHERE state IN ('PENDING','RETRY_WAIT') AND available_at <= now()
            ORDER BY available_at, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    /** 干净种子：先插交易单再插 outbox 行（FK 真实引用）；在途态自带租约（ck_outbox_lease_present）。 */
    private UUID seedOutboxRow(String state, int epoch, int attemptCount, boolean availableNow) {
        UUID orderId = seedTradeOrder("obx-" + UUID.randomUUID(), "live-obx",
                "DISCARDED", "NOT_PAY", "test-seed");
        UUID id = UUID.randomUUID();
        boolean inFlight = state.equals("CLAIMED") || state.equals("EXECUTING")
                || state.equals("RETRY_WAIT");
        arenaJdbc.sql("""
                INSERT INTO arena.oa_compensation_outbox(id,order_id,event_type,payload,state,
                    lease_owner,lease_until,lease_epoch,attempt_count,available_at,created_at,updated_at)
                VALUES (:id,:o,'RESOURCE_REFUND',
                    '[{"resourceType":"INVENTORY","deductionSeq":1,"quantity":1}]'::jsonb,
                    :state,
                    CASE WHEN :inflight THEN 'seed-worker' END,
                    CASE WHEN :inflight THEN now() + interval '30 seconds' END,
                    :epoch,:ac,
                    CASE WHEN :avail THEN now() - interval '1 second' ELSE now() + interval '10 minutes' END,
                    now(),now())
                """).param("id", id).param("o", orderId).param("state", state)
                .param("inflight", inFlight)
                .param("epoch", epoch).param("ac", attemptCount).param("avail", availableNow)
                .update();
        return id;
    }

    @Test
    void doubleWorkerSkipLockedNeverDoubleClaims() throws Exception {
        UUID a = seedOutboxRow("PENDING", 0, 0, true);
        UUID b = seedOutboxRow("PENDING", 0, 0, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<UUID> f1 = pool.submit(() -> claimOne(start));
        Future<UUID> f2 = pool.submit(() -> claimOne(start));
        start.countDown();
        UUID claim1 = f1.get(10, TimeUnit.SECONDS);
        UUID claim2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(claim1).isNotNull();
        assertThat(claim2).isNotNull();
        assertThat(List.of(claim1, claim2)).as("两 worker 领到两行，零重复")
                .containsExactlyInAnyOrder(a, b);
    }

    /** 模拟 worker：领一行（FOR UPDATE SKIP LOCKED + CAS 到 CLAIMED）后提交事务并返回 id。 */
    private UUID claimOne(CountDownLatch start) {
        return arenaTx.execute(status -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            List<UUID> ids = arenaJdbc.sql(CLAIM_SQL).query(UUID.class).list();
            if (ids.isEmpty()) {
                return null;
            }
            UUID id = ids.get(0);
            arenaJdbc.sql("""
                    UPDATE arena.oa_compensation_outbox
                    SET state='CLAIMED', lease_owner=:owner, lease_until=now()+interval '30 seconds',
                        lease_epoch=lease_epoch+1, updated_at=now()
                    WHERE id=:id AND state IN ('PENDING','RETRY_WAIT')
                    """).param("owner", "worker-" + Thread.currentThread().threadId())
                    .param("id", id).update();
            return id;
        });
    }

    @Test
    void expiredLeaseIsReclaimable() {
        UUID id = seedOutboxRow("CLAIMED", 3, 1, true);
        // 租约已过期（模拟 worker 崩溃）
        adminJdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET lease_owner='dead-worker', lease_until=now()-interval '1 minute'
                WHERE id=:id
                """).param("id", id).update();

        // 回收扫描：租约过期即可重领（epoch 再 +1）
        int reclaimed = arenaJdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state='PENDING', lease_owner=null, lease_until=null,
                    lease_epoch=lease_epoch+1, updated_at=now()
                WHERE id=:id AND state IN ('CLAIMED','EXECUTING') AND lease_until < now()
                """).param("id", id).update();
        assertThat(reclaimed).isEqualTo(1);

        UUID next = arenaJdbc.sql(CLAIM_SQL).query(UUID.class).single();
        assertThat(next).isEqualTo(id);
    }

    @Test
    void staleEpochTerminalCommitIsRejected() {
        UUID id = seedOutboxRow("CLAIMED", 5, 1, true);
        // 旧 epoch 的迟到提交（epoch=4）：CAS 栅栏拒写
        int stale = arenaJdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state='SUCCEEDED', finished_at=now(), lease_owner=null, lease_until=null,
                    updated_at=now()
                WHERE id=:id AND state='CLAIMED' AND lease_epoch=:epoch
                """).param("id", id).param("epoch", 4L).update();
        assertThat(stale).as("旧 epoch 提交终态必须 0 行受影响").isZero();

        // 当前 epoch 提交放行
        int current = arenaJdbc.sql("""
                UPDATE arena.oa_compensation_outbox
                SET state='SUCCEEDED', finished_at=now(), lease_owner=null, lease_until=null,
                    updated_at=now()
                WHERE id=:id AND state='CLAIMED' AND lease_epoch=:epoch
                """).param("id", id).param("epoch", 5L).update();
        assertThat(current).isEqualTo(1);
    }

    @Test
    void claimQueryPlanHitsClaimIndex() {
        seedOutboxRow("PENDING", 0, 0, true);
        String plan = arenaJdbc.sql("EXPLAIN SELECT " + CLAIM_SQL.substring(CLAIM_SQL.indexOf("id FROM")))
                .query(String.class).list().stream()
                .reduce((a, b) -> a + "\n" + b).orElse("");
        assertThat(plan).as("claim 计划必须命中 ix_outbox_claim（M2-05 验收）\n%s", plan)
                .contains("ix_outbox_claim");
    }

    @Test
    void terminalStateRequiresFinishedAt() {
        UUID orderId = seedTradeOrder("obx-term", "live-obx", "DISCARDED", "NOT_PAY", "test");
        assertThatThrownBy(() -> arenaJdbc.sql("""
                        INSERT INTO arena.oa_compensation_outbox(id,order_id,event_type,payload,state,
                            attempt_count,available_at,created_at,updated_at)
                        VALUES (:id,:o,'RESOURCE_REFUND','[]'::jsonb,'SUCCEEDED',1,now(),now(),now())
                        """).param("id", UUID.randomUUID()).param("o", orderId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_terminal_finished");
    }
}
