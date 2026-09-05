package com.objwww.pr.arena.it;

import com.objwww.pr.arena.domain.model.IdempotencyClaim;
import com.objwww.pr.arena.infrastructure.persistence.PostgresIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-08 幂等机制契约 IT（C-2 全语义，真 PG）：
 * 同 key 同 digest CONSUMED 重放；同 key 不同 digest 409 冲突；PROCESSING 租约内重入 202；
 * claim 后崩溃（租约过期）回收重领 epoch+1；旧 owner 的 complete 被 epoch 栅栏拒绝；
 * 并发 32 认领同 key 恰好一个 CLAIMED。
 */
class PostgresIdempotencyRepositoryIT extends ArenaPostgresITBase {

    private PostgresIdempotencyRepository repo;

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofMinutes(10);

    @BeforeEach
    void initRepo() {
        repo = new PostgresIdempotencyRepository(arenaJdbc);
    }

    private static String digest(String seed) {
        return com.objwww.pr.shared.Digest.sha256Of(seed).value();
    }

    @Test
    void claimThenCompleteThenReplay() {
        UUID orderId = UUID.randomUUID();
        IdempotencyClaim first = repo.claim("i-replay", digest("req"), "w1", LEASE, TTL);
        assertThat(first).isEqualTo(new IdempotencyClaim.Claimed(0));

        assertThat(repo.complete("i-replay", 0, orderId, digest("resp"))).isTrue();

        IdempotencyClaim second = repo.claim("i-replay", digest("req"), "w2", LEASE, TTL);
        assertThat(second).isEqualTo(new IdempotencyClaim.Replay(orderId, digest("resp")));
    }

    @Test
    void sameKeyDifferentDigestConflicts() {
        assertThat(repo.claim("i-conflict", digest("req-a"), "w1", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Claimed(0));
        assertThat(repo.claim("i-conflict", digest("req-b"), "w2", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Conflict());
    }

    @Test
    void inProgressLeaseReturnsBusy() {
        assertThat(repo.claim("i-busy", digest("req"), "w1", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Claimed(0));
        assertThat(repo.claim("i-busy", digest("req"), "w2", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.InProgress());
    }

    @Test
    void crashAfterClaimIsReclaimedWithEpochBump() {
        // w1 认领后"崩溃"：租约拨到过去，模拟无人 complete
        assertThat(repo.claim("i-crash", digest("req"), "w1", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Claimed(0));
        adminJdbc.sql("""
                UPDATE arena.oa_idempotency_record SET lease_until = now() - interval '1 second'
                WHERE intent_id = 'i-crash'
                """).update();

        IdempotencyClaim reclaimed = repo.claim("i-crash", digest("req"), "w2", LEASE, TTL);
        assertThat(reclaimed).isEqualTo(new IdempotencyClaim.Claimed(1));

        // 旧 owner（epoch=0）的迟到 complete 被栅栏拒绝
        assertThat(repo.complete("i-crash", 0, UUID.randomUUID(), digest("resp"))).isFalse();
        // 新 owner 正常收口
        assertThat(repo.complete("i-crash", 1, UUID.randomUUID(), digest("resp"))).isTrue();
    }

    @Test
    void releaseReturnsIntentToFresh() {
        assertThat(repo.claim("i-release", digest("req"), "w1", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Claimed(0));
        repo.release("i-release", 0);
        assertThat(repo.claim("i-release", digest("req"), "w2", LEASE, TTL))
                .isEqualTo(new IdempotencyClaim.Claimed(1));
    }

    @Test
    void concurrentClaimsProduceExactlyOneClaimed() throws Exception {
        int concurrency = 32;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<IdempotencyClaim> outcomes = new CopyOnWriteArrayList<>();
        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final String worker = "w" + i;
            tasks.add(() -> {
                start.await();
                outcomes.add(repo.claim("i-concurrent", digest("req"), worker, LEASE, TTL));
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        long claimed = outcomes.stream()
                .filter(c -> c instanceof IdempotencyClaim.Claimed).count();
        long replayOrBusy = outcomes.stream()
                .filter(c -> c instanceof IdempotencyClaim.InProgress
                        || c instanceof IdempotencyClaim.Replay).count();
        assertThat(claimed).as("32 并发同 key 认领：恰好一个 CLAIMED").isEqualTo(1);
        assertThat(claimed + replayOrBusy).as("其余全部为确定性重入语义（无 CONFLICT/异常）")
                .isEqualTo(concurrency);
        assertThat(outcomes.stream().filter(c -> c instanceof IdempotencyClaim.Conflict)).isEmpty();
    }
}
