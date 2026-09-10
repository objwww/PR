package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-A4b F19 IT（L1 真 PG；本地无 docker 自动跳过，195 部署段真跑）：
 * incident_key 唯一首锁并发契约。两事务并发同键 INSERT：
 * <ul>
 *   <li>恰一行落库；</li>
 *   <li>双方正常返回——输家 false、不抛 23505（ON CONFLICT DO NOTHING；普通 INSERT
 *       唯一冲突后 PG 事务中断，catch+重查面失效，正是本卡翻转的缺陷面）；</li>
 *   <li>输家同事务后续语句可用（锁行读 + UPDATE 照常提交）= 无中断毒面；</li>
 *   <li>他方回滚释放键 → 输家插成 true（首锁归属正确换手）。</li>
 * </ul>
 * 并发编排为确定性竞争：赢家先插持锁不提交，输家在唯一索引元组上等锁，
 * 由赢家提交/回滚裁定输家的 false/true 两面。
 */
class ExA4bIncidentConcurrencyIT extends PostgresITBase {

    private PostgresIncidentRepository incidents;

    @BeforeEach
    void setUp() {
        incidents = new PostgresIncidentRepository(JdbcClient.create(controlDataSource()));
    }

    private static Incident incident(String key) {
        Instant now = Instant.now();
        return new Incident(UUID.randomUUID(), key, IncidentStatus.FIRING, 0,
                now, now, null, null, null, 0, 0, 0, null, now, now, now, now);
    }

    @Test
    @DisplayName("F19 两事务并发同键：恰一行、输家 false 无异常、同事务后续语句可用")
    void f19_conflictLoserContinuesSameTransactionWithoutPoison() throws Exception {
        String key = "alertname=HighErrorRate|service=concur-f19";
        AtomicReference<UUID> winnerId = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch winnerInserted = new CountDownLatch(1);
            CountDownLatch winnerRelease = new CountDownLatch(1);

            // 赢家：先插（true）持锁不提交，等主线程裁定后提交
            Future<Boolean> winner = pool.submit(() ->
                    controlTx.execute(status -> {
                        Incident candidate = incident(key);
                        winnerId.set(candidate.id());
                        boolean fresh = incidents.insert(candidate);
                        winnerInserted.countDown();
                        try {
                            winnerRelease.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return fresh;
                    }));

            assertThat(winnerInserted.await(30, TimeUnit.SECONDS)).isTrue();

            // 输家：在赢家未提交的索引元组上等锁；赢家提交后 ON CONFLICT 命中 → false。
            // 契约核心不是 false 本身，而是没有异常、且同事务锁行读 + UPDATE 照常提交。
            AtomicReference<UUID> loserSeenId = new AtomicReference<>();
            Future<Boolean> loser = pool.submit(() ->
                    controlTx.execute(status -> {
                        boolean fresh = incidents.insert(incident(key));
                        Incident existing = incidents.findByKeyForUpdate(key).orElseThrow();
                        loserSeenId.set(existing.id());
                        incidents.update(withReceivedPlusOne(existing));
                        return fresh;
                    }));

            winnerRelease.countDown();
            assertThat(winner.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(loser.get(30, TimeUnit.SECONDS))
                    .as("输家插键正常返回 false（不抛 23505）").isFalse();

            // 恰一行，且是赢家的；输家的同事务 UPDATE 已随其提交（received 0→1）
            assertThat(count("incident")).isEqualTo(1);
            assertThat(winnerId.get()).isNotNull();
            assertThat(adminJdbc.sql("SELECT id::text FROM incident")
                    .query(String.class).single()).isEqualTo(winnerId.get().toString());
            assertThat(adminJdbc.sql("SELECT received_count FROM incident")
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(loserSeenId.get()).isEqualTo(winnerId.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("F19 赢家回滚释放键：输家等到 abort 后插成 true（首锁正确换手）")
    void f19_winnerRollbackFreesKeyForLoser() throws Exception {
        String key = "alertname=HighErrorRate|service=concur-rb";
        AtomicReference<UUID> loserRowId = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch winnerInserted = new CountDownLatch(1);
            CountDownLatch winnerRelease = new CountDownLatch(1);

            // 赢家：先插持锁，裁定后抛异常 → 事务回滚
            Future<Boolean> winner = pool.submit(() ->
                    controlTx.execute(status -> {
                        incidents.insert(incident(key));
                        winnerInserted.countDown();
                        try {
                            winnerRelease.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        throw new IllegalStateException("drill-abort");
                    }));

            assertThat(winnerInserted.await(30, TimeUnit.SECONDS)).isTrue();

            // 输家：锁上等到赢家 abort → 元组消失 → 自己插成 true
            Future<Boolean> loser = pool.submit(() ->
                    controlTx.execute(status -> {
                        Incident candidate = incident(key);
                        boolean fresh = incidents.insert(candidate);
                        if (fresh) {
                            loserRowId.set(candidate.id());
                        }
                        return fresh;
                    }));

            winnerRelease.countDown();
            assertThatThrownBy(() -> winner.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(loser.get(30, TimeUnit.SECONDS))
                    .as("他方回滚后键可复用，输家插成 true").isTrue();

            assertThat(count("incident")).isEqualTo(1);
            assertThat(loserRowId.get()).isNotNull();
            assertThat(adminJdbc.sql("SELECT id::text FROM incident")
                    .query(String.class).single()).isEqualTo(loserRowId.get().toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("F24 红驱扫描查询面真 PG 回路：waiting 行可查、非 FIRING/无等待被排除")
    void f24_redriveScanQueryRoundTripsOnRealPg() {
        Instant now = Instant.now();
        // 在等：FIRING + waiting_reason → 必中
        Incident waiting = new Incident(UUID.randomUUID(),
                "alertname=HighErrorRate|service=scan-wait", IncidentStatus.FIRING, 0,
                now, now, null, null, null, 0, 0, 0, null, now, now, now, now, "DEFERRED");
        assertThat(incidents.insert(waiting)).isTrue();
        // 排除面：FIRING 无等待
        assertThat(incidents.insert(incident("alertname=HighErrorRate|service=scan-plain"))).isTrue();
        // 排除面：RESOLVED 但残留等待（resolved 即清等待，防御面仍只认 FIRING）
        assertThat(incidents.insert(new Incident(UUID.randomUUID(),
                "alertname=HighErrorRate|service=scan-done", IncidentStatus.RESOLVED, 0,
                now, now, now, null, null, 0, 0, 0, null, now, now, now, now, "DEFERRED")))
                .isTrue();

        var waitingRows = incidents.findWaitingForRedrive();
        assertThat(waitingRows).extracting(Incident::id).containsExactly(waiting.id());
        assertThat(waitingRows.get(0).waitingReason()).isEqualTo("DEFERRED");
    }

    // ------------------------------------------------------------------ 助手

    /** 输家同事务续用面：全字段原样拷贝，仅 received+1、updated_at 前移（18 参全形）。 */
    private static Incident withReceivedPlusOne(Incident i) {
        return new Incident(i.id(), i.incidentKey(), i.status(), i.generation(),
                i.episodeStartedAt(), i.lastFiringStartsAt(), i.resolvedAt(),
                i.lastInvestigationHash(), i.pendingInvestigationHash(),
                i.receivedCount() + 1, i.distinctEventCount(), i.notificationCount(),
                i.currentRcaRunId(), i.firstSeenAt(), i.lastEventAt(),
                i.createdAt(), Instant.now(), i.waitingReason());
    }
}
