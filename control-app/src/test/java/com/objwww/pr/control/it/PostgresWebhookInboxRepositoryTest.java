package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.infrastructure.persistence.PostgresWebhookInboxRepository;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresWebhookInboxRepository 组件规则测试（M1 技术方案 v1.2 §11）：
 * CT-12 并发领取唯一性 / CT-13 租约回收 / CT-15 晚到回写零效果 / CT-17 公平领取顺序。
 *
 * <p>命名说明：本类是 IT（需 Testcontainers PG 16），按任务约定以 *Test 命名；
 * 无 docker 环境由 PostgresITBase 的 disabledWithoutDocker 自动跳过，
 * 本机 mvn test 不受影响，留待 195（docker 环境）执行。
 */
class PostgresWebhookInboxRepositoryTest extends PostgresITBase {

    private PostgresWebhookInboxRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PostgresWebhookInboxRepository(controlJdbc);
    }

    // ------------------------------------------------------------------ CT-12

    /** CT-12：并发领取——20 线程抢同批 RECEIVED，每行恰好被一个 worker 领到（SKIP LOCKED） */
    @Test
    void concurrentClaimGrantsEachRowExactlyOnce() throws Exception {
        int rowCount = 50;
        for (int i = 0; i < rowCount; i++) {
            insertReceived("ct12-" + i);
        }

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                String worker = "worker-" + t;
                futures.add(pool.submit(() -> {
                    List<String> claimed = new ArrayList<>();
                    while (true) {
                        List<WebhookInbox> batch = repo.claim(3, worker, Duration.ofMinutes(5));
                        if (batch.isEmpty()) {
                            break;
                        }
                        for (WebhookInbox inbox : batch) {
                            // 领取即持租约：PROCESSING + owner 是自己 + epoch+1
                            assertThat(inbox.getState()).isEqualTo(InboxState.PROCESSING);
                            assertThat(inbox.getLeaseOwner()).isEqualTo(worker);
                            assertThat(inbox.getLeaseEpoch()).isEqualTo(1);
                            claimed.add(inbox.getDeliveryId());
                        }
                    }
                    return claimed;
                }));
            }
            List<String> all = new ArrayList<>();
            for (Future<List<String>> f : futures) {
                all.addAll(f.get());
            }
            assertThat(all).hasSize(rowCount);
            assertThat(all).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }

        Long processing = adminJdbc.sql(
                        "SELECT count(*) FROM webhook_inbox WHERE state = 'PROCESSING'")
                .query(Long.class).single();
        assertThat(processing).isEqualTo((long) rowCount);
    }

    // ------------------------------------------------------------------ CT-13

    /** CT-13：租约回收——PROCESSING 且 lease_until 过期可重领（epoch+1）；未过期不误收 */
    @Test
    void expiredLeaseCanBeReclaimedWhileLiveLeaseCannot() {
        insertReceived("ct13-live");
        insertReceived("ct13-expired");

        List<WebhookInbox> first = repo.claim(2, "worker-a", Duration.ofMinutes(10));
        assertThat(first).hasSize(2);

        // 两条租约都未过期：其他 worker 领不到
        assertThat(repo.claim(2, "worker-b", Duration.ofMinutes(10))).isEmpty();

        // 测试动作（admin 角色直接拨 lease_until）：ct13-expired 的租约过期
        adminJdbc.sql("""
                UPDATE webhook_inbox SET lease_until = now() - interval '1 second'
                 WHERE delivery_id = 'ct13-expired'
                """).update();

        List<WebhookInbox> reclaimed = repo.claim(2, "worker-b", Duration.ofMinutes(10));
        assertThat(reclaimed).hasSize(1);
        WebhookInbox row = reclaimed.get(0);
        assertThat(row.getDeliveryId()).isEqualTo("ct13-expired");
        assertThat(row.getState()).isEqualTo(InboxState.PROCESSING);
        assertThat(row.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(row.getLeaseEpoch()).isEqualTo(2); // 重领 epoch+1，栅栏旧 worker（I14）

        // 未过期那条仍不被误收
        assertThat(repo.claim(2, "worker-c", Duration.ofMinutes(10))).isEmpty();
    }

    // ------------------------------------------------------------------ CT-15

    /** CT-15：租约被接管后旧 Processor 晚到回写——epoch 失配 0 行，状态不被改写（I14） */
    @Test
    void lateWritebackWithStaleEpochTouchesZeroRows() {
        insertReceived("ct15-d1");

        WebhookInbox byA = repo.claim(1, "worker-a", Duration.ofMinutes(10)).get(0);
        assertThat(byA.getLeaseEpoch()).isEqualTo(1);

        // 崩溃回收：租约过期 → worker-b 接管（epoch 2）
        adminJdbc.sql("""
                UPDATE webhook_inbox SET lease_until = now() - interval '1 second'
                 WHERE delivery_id = 'ct15-d1'
                """).update();
        WebhookInbox byB = repo.claim(1, "worker-b", Duration.ofMinutes(10)).get(0);
        assertThat(byB.getLeaseEpoch()).isEqualTo(2);

        // 旧 worker-a 的晚到回写：四个完成口全部 0 行
        assertThat(repo.completeProcessed("ct15-d1", "worker-a", 1)).isZero();
        assertThat(repo.completeRetryWait("ct15-d1", "worker-a", 1,
                Duration.ofSeconds(30), "{\"error\":\"late\"}")).isZero();
        assertThat(repo.completeDeadLetter("ct15-d1", "worker-a", 1,
                "{\"error\":\"late\"}")).isZero();
        assertThat(repo.completeIgnored("ct15-d1", "worker-a", 1)).isZero();

        // 行仍归 worker-b 的 PROCESSING，attempt 不被晚到回写偷加
        WebhookInbox current = repo.findByDeliveryId("ct15-d1").orElseThrow();
        assertThat(current.getState()).isEqualTo(InboxState.PROCESSING);
        assertThat(current.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(current.getLeaseEpoch()).isEqualTo(2);
        assertThat(current.getAttemptCount()).isZero();
        assertThat(current.getProcessedAt()).isNull();

        // worker-b 的正常回写生效
        assertThat(repo.completeProcessed("ct15-d1", "worker-b", 2)).isEqualTo(1);
        current = repo.findByDeliveryId("ct15-d1").orElseThrow();
        assertThat(current.getState()).isEqualTo(InboxState.PROCESSED);
        assertThat(current.getProcessedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ CT-17

    /**
     * CT-17：公平领取——RECEIVED（next_retry_at NULLS FIRST）先于到点 RETRY_WAIT；
     * 同级按时间升序；未到点 RETRY_WAIT 不领；小 limit 逐轮领取无尾部饿死。
     */
    @Test
    void claimFollowsFairOrderingAndDoesNotStarveTail() {
        // 直接 SQL 造可控时间戳（insertNew 的 received_at 固定 now()，区分不了顺序）
        insertAt("ct17-r1", "RECEIVED", 0, null, "2024-01-01 00:00:02+00");
        insertAt("ct17-r2", "RECEIVED", 0, null, "2024-01-01 00:00:01+00"); // 更早，应先被领
        insertAt("ct17-w1", "RETRY_WAIT", 1, "2024-01-01 00:00:03+00", "2024-01-01 00:00:03+00");
        insertAt("ct17-w2", "RETRY_WAIT", 1, "2024-01-01 00:00:04+00", "2024-01-01 00:00:04+00");
        insertAt("ct17-future", "RETRY_WAIT", 1, "2999-01-01 00:00:00+00", "2024-01-01 00:00:05+00");

        // limit=1 逐条领取（每轮只取队首，等价多轮小批量），断言公平顺序：
        // RECEIVED 按 received_at 升序 → 到点 RETRY_WAIT 按 next_retry_at 升序
        List<String> order = new ArrayList<>();
        while (true) {
            List<WebhookInbox> batch = repo.claim(1, "worker-fair", Duration.ofMinutes(10));
            if (batch.isEmpty()) {
                break;
            }
            order.add(batch.get(0).getDeliveryId());
        }
        assertThat(order).containsExactly("ct17-r2", "ct17-r1", "ct17-w1", "ct17-w2");

        // 未到点的 RETRY_WAIT 不被领取（退避语义）
        assertThat(repo.findByDeliveryId("ct17-future").orElseThrow().getState())
                .isEqualTo(InboxState.RETRY_WAIT);
    }

    // ------------------------------------------------------------------ 基础契约

    /** insertNew 幂等（I9：主键冲突=false 原行不动）+ payloadRaw 按需取回 + 畸形 JSON 落 NULL（E2E-22 形状） */
    @Test
    void insertNewIsIdempotentAndPayloadRawRoundTrips() {
        byte[] raw = "{\"pr\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(repo.insertNew("dup-1", "pull_request", "opened",
                1234L, 5678L, raw, "{\"pr\":1}", Digests.sha256Hex("dup-1"))).isTrue();
        // 重投：主键冲突 → false，原行 digest 不被覆盖（I13）
        assertThat(repo.insertNew("dup-1", "pull_request", "opened",
                1234L, 5678L, raw, "{\"pr\":1}", Digests.sha256Hex("tampered"))).isFalse();
        WebhookInbox row = repo.findByDeliveryId("dup-1").orElseThrow();
        assertThat(row.getPayloadDigest()).isEqualTo(Digests.sha256Hex("dup-1"));
        assertThat(row.getState()).isEqualTo(InboxState.RECEIVED);

        // raw 字节原样取回（CT-18 前提：HMAC 永远对 raw 复核）
        assertThat(repo.payloadRaw("dup-1")).isEqualTo(raw);
        assertThat(repo.payloadRaw("no-such-row")).isNull();

        // 畸形 JSON：payloadJson 传 null 也能落库（E2E-22：合法签名 + 畸形 JSON 留 raw 审计）
        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);
        assertThat(repo.insertNew("malformed-1", "pull_request", "opened",
                null, null, malformed, null, Digests.sha256Hex("malformed-1"))).isTrue();
        assertThat(repo.payloadRaw("malformed-1")).isEqualTo(malformed);
    }

    // ------------------------------------------------------------------ 测试动作助手

    /** 经 port 落一行 RECEIVED（received_at=DB now()） */
    private void insertReceived(String deliveryId) {
        byte[] raw = ("{\"delivery\":\"" + deliveryId + "\"}").getBytes(StandardCharsets.UTF_8);
        boolean inserted = repo.insertNew(deliveryId, "pull_request", "opened",
                1234L, 5678L, raw, new String(raw, StandardCharsets.UTF_8),
                Digests.sha256Hex(deliveryId));
        assertThat(inserted).isTrue();
    }

    /** admin 直接造指定状态/时间戳的行（CT-17 顺序控制用；nextRetryAt 可空） */
    private void insertAt(String deliveryId, String state, int attemptCount,
                          String nextRetryAt, String receivedAt) {
        adminJdbc.sql("""
                INSERT INTO webhook_inbox (
                    delivery_id, github_event, github_action,
                    payload_raw, payload_json, payload_digest,
                    state, attempt_count, next_retry_at, received_at, updated_at
                ) VALUES (
                    :id, 'pull_request', 'opened',
                    decode('01', 'hex'), '{}'::jsonb, :digest,
                    :state, :attemptCount,
                    CAST(:nextRetryAt AS timestamptz), CAST(:receivedAt AS timestamptz), now()
                )
                """)
                .param("id", deliveryId)
                .param("digest", Digests.sha256Hex(deliveryId))
                .param("state", state)
                .param("attemptCount", attemptCount)
                .param("nextRetryAt", nextRetryAt)
                .param("receivedAt", receivedAt)
                .update();
    }
}
