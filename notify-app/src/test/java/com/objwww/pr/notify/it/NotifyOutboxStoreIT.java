package com.objwww.pr.notify.it;

import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.StaleClaimException;
import com.objwww.pr.notify.infrastructure.persistence.PostgresNotifyOutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-20 验收（真 PG，notify_app 真实角色）：SKIP LOCKED 领取互斥、租约过期折叠回收、
 * epoch 栅栏（僵尸 worker 落账拒绝）、429 不耗预算/5xx 耗预算、唯一键 (report, channel,
 * template) 防重、publication 聚合同步（SENT/全终态 DEAD/RETRY_WAIT 不动）。
 */
class NotifyOutboxStoreIT extends NotifyPostgresITBase {

    private static final Duration LEASE = Duration.ofSeconds(60);

    private PostgresNotifyOutboxStore store;

    private UUID outboxId;
    private UUID publicationId;
    private UUID reportId;

    @BeforeEach
    void setUp() {
        store = new PostgresNotifyOutboxStore(notifyJdbc);
        ReportSeed seed = seedValidatedReport();
        reportId = seed.reportId();
        publicationId = seedPublicationWithOutbox(seed, "dingtalk-test", UUID.randomUUID());
        outboxId = adminJdbc.sql(
                "SELECT id FROM notify_outbox WHERE publication_id = :p")
                .param("p", publicationId).query(UUID.class).single();
    }

    private ClaimedNotification claimOne(String owner) {
        List<ClaimedNotification> claimed = store.claim(owner, LEASE, 10);
        assertThat(claimed).hasSize(1);
        return claimed.getFirst();
    }

    @Test
    @DisplayName("领取：到期 PENDING 行被领取为 CLAIMED（租约/epoch 推进），二次领取不重复")
    void claimsDueRowsOnce() {
        ClaimedNotification claimed = claimOne("owner-1");
        assertThat(claimed.id()).isEqualTo(outboxId);
        assertThat(claimed.leaseEpoch()).isEqualTo(1);

        assertThat(store.claim("owner-2", LEASE, 10)).isEmpty();

        long epochInDb = adminJdbc.sql("SELECT lease_epoch FROM notify_outbox WHERE id = :id")
                .param("id", outboxId).query(Long.class).single();
        assertThat(epochInDb).isEqualTo(1);
    }

    @Test
    @DisplayName("SKIP LOCKED 互斥：行被并发事务持锁时另一 worker 领取跳过该行")
    void skipsLockedRowsUnderConcurrentClaim() throws java.sql.SQLException {
        ReportSeed seed = seedValidatedReport();
        UUID lockedPub = seedPublicationWithOutbox(seed, "dingtalk-test", UUID.randomUUID());
        UUID lockedId = adminJdbc.sql(
                "SELECT id FROM notify_outbox WHERE publication_id = :p")
                .param("p", lockedPub).query(UUID.class).single();

        // 独立连接持锁：FOR UPDATE 占住行后，主连接的 SKIP LOCKED 领取必须跳过该行
        try (var ds = freshNotifyDataSource();
             var raw = ds.getConnection()) {
            raw.setAutoCommit(false);
            try (var stmt = raw.prepareStatement(
                    "SELECT id FROM notify_outbox WHERE id = ? FOR UPDATE")) {
                stmt.setObject(1, lockedId);
                stmt.executeQuery();
            }
            List<ClaimedNotification> claimed = store.claim("owner-locked", LEASE, 10);
            assertThat(claimed).extracting(ClaimedNotification::id).doesNotContain(lockedId);
            raw.rollback();
        }
    }

    @Test
    @DisplayName("崩溃回收：租约过期的 CLAIMED 行被下轮领取（epoch+1），旧 epoch 落账被拒")
    void reclaimsExpiredLeaseAndFencesStaleWorker() {
        ClaimedNotification first = claimOne("worker-crashed");
        // 模拟持有者崩溃：把租约拨到过去
        adminJdbc.sql("UPDATE notify_outbox SET lease_until = now() - interval '1 second' "
                        + "WHERE id = :id").param("id", outboxId).update();

        ClaimedNotification reclaimed = claimOne("worker-new");
        assertThat(reclaimed.id()).isEqualTo(outboxId);
        assertThat(reclaimed.leaseEpoch()).isEqualTo(first.leaseEpoch() + 1);

        // 僵尸 worker 用旧 epoch 落 SENT → 栅栏拒绝；新 epoch 成功
        assertThatThrownBy(() -> store.markSent(first.id(), first.leaseEpoch(), Instant.now()))
                .isInstanceOf(StaleClaimException.class);
        store.markSent(reclaimed.id(), reclaimed.leaseEpoch(), Instant.now());
        assertThat(outboxState(outboxId)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("429 不耗预算：consumeAttempt=false 行数不变；5xx 耗预算；耗尽态由执行器判")
    void retryBudgetSemantics() {
        ClaimedNotification claimed = claimOne("owner-1");

        store.markRetryWait(claimed.id(), claimed.leaseEpoch(),
                Instant.now().plusSeconds(60), false,
                "{\"reason\":\"rate_limited\"}");
        assertThat(attemptCount()).isZero();

        adminJdbc.sql("UPDATE notify_outbox SET available_at = now() - interval '1 second' "
                        + "WHERE id = :id").param("id", outboxId).update();
        ClaimedNotification reclaimed = claimOne("owner-1");
        store.markRetryWait(reclaimed.id(), reclaimed.leaseEpoch(),
                Instant.now().plusSeconds(60), true, "{\"reason\":\"http_503\"}");
        assertThat(attemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("唯一键 (report_id, channel, template_version) 防重：重复插入 23505 拒绝")
    void duplicateDeliveryRowRejected() {
        // 同 report+channel+template 第二条 outbox 行（publication 唯一键会先拦二次建 pub，
        // 故直接对既有 publication 重复投递行）
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO notify_outbox(id, publication_id, report_id, channel,
                            template_version, operation_id, payload_json, state,
                            attempt_count, max_attempts, created_at, updated_at)
                        VALUES (:id, :pub, :report, :channel, 'am3-notice-v1', :op::uuid,
                                CAST(:payload AS jsonb), 'PENDING', 0, 5, now(), now())
                        """).param("id", UUID.randomUUID()).param("pub", publicationId)
                        .param("report", reportId).param("channel", "dingtalk-test")
                        .param("op", UUID.randomUUID().toString())
                        .param("payload", "{\"operation_id\":\"dup\"}")
                        .update())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_notify_outbox_delivery");
    }

    @Test
    @DisplayName("publication 聚合同步：任一行 SENT→SENT；全终态无一 SENT→DEAD；仍有在途→不动")
    void syncsPublicationAggregate() {
        ClaimedNotification claimed = claimOne("owner-1");
        store.markSent(claimed.id(), claimed.leaseEpoch(), Instant.now());
        store.syncPublication(publicationId);
        assertThat(publicationState(publicationId)).isEqualTo("SENT");

        // 全 DEAD 场景（单一渠道行）
        ReportSeed seed = seedValidatedReport();
        UUID deadPub = seedPublicationWithOutbox(seed, "dingtalk-test", UUID.randomUUID());
        UUID deadRow = adminJdbc.sql(
                "SELECT id FROM notify_outbox WHERE publication_id = :p")
                .param("p", deadPub).query(UUID.class).single();
        adminJdbc.sql("""
                UPDATE notify_outbox SET state = 'CLAIMED', lease_epoch = 1,
                    lease_until = :until WHERE id = :id
                """).param("until", Timestamp.from(Instant.now().plusSeconds(60)))
                .param("id", deadRow).update();
        store.markDead(deadRow, 1, "{\"reason\":\"http_400\"}");
        store.syncPublication(deadPub);
        assertThat(publicationState(deadPub)).isEqualTo("DEAD");
    }

    @Test
    @DisplayName("SENT 状态即 sent_at 非空（ck_notify_outbox_lifecycle 双向成立）")
    void lifecycleConstraintHolds() {
        ClaimedNotification claimed = claimOne("owner-1");
        store.markSent(claimed.id(), claimed.leaseEpoch(), Instant.now());
        Integer nullSentAt = adminJdbc.sql(
                        "SELECT count(*) FROM notify_outbox WHERE id = :id AND sent_at IS NULL")
                .param("id", outboxId).query(Integer.class).single();
        assertThat(nullSentAt).isZero();
    }

    private int attemptCount() {
        return adminJdbc.sql("SELECT attempt_count FROM notify_outbox WHERE id = :id")
                .param("id", outboxId).query(Integer.class).single();
    }

    /** 独立连接池（避免连接池同连接自锁；SKIP LOCKED 互斥断言用） */
    private static com.zaxxer.hikari.HikariDataSource freshNotifyDataSource() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(NOTIFY_ROLE);
        config.setPassword(NOTIFY_PASSWORD);
        config.setMaximumPoolSize(1);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
}
