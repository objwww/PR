package com.objwww.pr.notify.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.StaleClaimException;
import com.objwww.pr.notify.infrastructure.persistence.PostgresDutyNotifyAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-14 duty_delivery 领取面（真 PG + 真角色 + Flyway V43 全链）：
 * 适配器 claim/mark 语义与 notify_outbox 同构；notify_app 对 V43 的授权边界
 * （无 INSERT duty_delivery——补投行只由 control_app watcher 铸造；duty_notification
 * 只读）。
 *
 * <p>B-55（195 真 PG 首跑暴露的 IT 自身缺陷，非适配器缺陷）：
 * ①payload_json 是 jsonb——PG 文本渲染在冒号后带空格（{@code "type": "duty"}），
 * 断言必须走 JSON 语义（parse 后取字段），不能比对无空格字面量；
 * ②单次领取内只允许一次 mark（WHERE state='CLAIMED' 栅栏）——重试语义 =
 * 行回 RETRY_WAIT 后<b>再领取</b>进入下一尝试周期，双重 markRetryWait 测试模式非法；
 * ③种下的行必须 @AfterEach 清干净——195 verify 打的是共享 pr_agent，遗留 PENDING
 * 行会污染下一轮 claim 断言。
 */
class DutyDeliveryClaimIT extends NotifyPostgresITBase {

    private final PostgresDutyNotifyAdapter adapter = new PostgresDutyNotifyAdapter(notifyJdbc);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Seed> seeds = new ArrayList<>();

    /** 种子行全量 id（@AfterEach 子先父后清理用） */
    private record Seed(UUID notificationId, UUID memberId, UUID ch1, UUID ch2,
                        UUID scheduleId, UUID layerId) {
    }

    /** 种子：成员/两通道/排班 + 一条 firing 通知 + 首投行（PENDING, priority 1） */
    private UUID seedDutyPair() {
        UUID memberId = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID layerId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO duty_member(id, name, created_at, updated_at)
                VALUES (:id, 'alice', now(), now())
                """).param("id", memberId).update();
        adminJdbc.sql("""
                INSERT INTO duty_channel(id, name, platform, env_key_webhook, priority,
                    is_fallback, enabled, created_at, updated_at)
                VALUES (:id, 'duty-primary', 'DINGTALK', 'NOTIFY_CHANNEL_DUTY_PRIMARY_WEBHOOK',
                    1, false, true, now(), now())
                """).param("id", ch1).update();
        adminJdbc.sql("""
                INSERT INTO duty_channel(id, name, platform, env_key_webhook, priority,
                    is_fallback, enabled, created_at, updated_at)
                VALUES (:id, 'duty-emergency', 'WECOM', 'NOTIFY_CHANNEL_DUTY_EMERGENCY_WEBHOOK',
                    9, true, true, now(), now())
                """).param("id", ch2).update();
        adminJdbc.sql("""
                INSERT INTO duty_schedule(id, name, rotation, anchor_date, handoff_time,
                    schedule_version, created_at, updated_at)
                VALUES (:id, 'primary', 'DAILY', '2026-09-07', '09:00', 1, now(), now())
                """).param("id", scheduleId).update();
        adminJdbc.sql("""
                INSERT INTO duty_layer(id, schedule_id, layer_index, created_at)
                VALUES (:id, :sid, 0, now())
                """).param("id", layerId).param("sid", scheduleId).update();
        adminJdbc.sql("""
                INSERT INTO duty_layer_member(layer_id, member_id, position)
                VALUES (:lid, :mid, 0)
                """).param("lid", layerId).param("mid", memberId).update();
        adminJdbc.sql("""
                INSERT INTO duty_notification(id, source, episode_id, alert_fingerprint,
                    event_status, severity, title, body_text, payload_json, fingerprint,
                    status, created_at)
                VALUES (:id, 'RCA_SYSTEM', 'ep-1', 'afp-1', 'firing', 'P2', 't', 'b',
                    CAST(:payload AS jsonb), 'fp-1', 'UNREAD', now())
                """).param("id", notificationId)
                .param("payload", "{\"type\":\"duty\",\"operation_id\":\""
                        + UUID.randomUUID() + "\",\"source\":\"RCA_SYSTEM\"}")
                .update();
        adminJdbc.sql("""
                INSERT INTO duty_delivery(id, notification_id, channel_id, priority, state,
                    lease_epoch, attempt_count, max_attempts, available_at, created_at, updated_at)
                VALUES (:id, :nid, :ch, 1, 'PENDING', 0, 0, 5, now(), now(), now())
                """).param("id", UUID.randomUUID()).param("nid", notificationId)
                .param("ch", ch1).update();
        seeds.add(new Seed(notificationId, memberId, ch1, ch2, scheduleId, layerId));
        return notificationId;
    }

    /** 领取后按 notification_id 认领本测试的行（共享库可能有他源到期行——B-55③） */
    private ClaimedNotification claimMine(UUID notificationId) {
        return adapter.claim("it-worker", Duration.ofSeconds(60), 10).stream()
                .filter(c -> notificationId.equals(c.publicationId()))
                .findFirst().orElseThrow();
    }

    @AfterEach
    void cleanSeededRows() {
        for (Seed s : seeds) {
            adminJdbc.sql("DELETE FROM duty_delivery WHERE notification_id = :id")
                    .param("id", s.notificationId()).update();
            adminJdbc.sql("DELETE FROM duty_notification WHERE id = :id")
                    .param("id", s.notificationId()).update();
            adminJdbc.sql("DELETE FROM duty_layer_member WHERE layer_id = :id")
                    .param("id", s.layerId()).update();
            adminJdbc.sql("DELETE FROM duty_layer WHERE id = :id")
                    .param("id", s.layerId()).update();
            adminJdbc.sql("DELETE FROM duty_schedule WHERE id = :id")
                    .param("id", s.scheduleId()).update();
            adminJdbc.sql("DELETE FROM duty_channel WHERE id IN (:a, :b)")
                    .param("a", s.ch1()).param("b", s.ch2()).update();
            adminJdbc.sql("DELETE FROM duty_member WHERE id = :id")
                    .param("id", s.memberId()).update();
        }
        seeds.clear();
    }

    @Test
    @DisplayName("claim 领取 duty 行：渠道名 JOIN、payload 随行（jsonb 语义断言）、epoch 推进")
    void claimReturnsDutyRowWithChannelName() throws Exception {
        UUID notificationId = seedDutyPair();

        ClaimedNotification n = claimMine(notificationId);

        assertThat(n.channel()).isEqualTo("duty-primary");
        JsonNode payload = mapper.readTree(n.payloadJson());
        assertThat(payload.path("type").asText()).isEqualTo("duty");
        assertThat(payload.path("source").asText()).isEqualTo("RCA_SYSTEM");
        assertThat(payload.path("operation_id").asText()).isNotEmpty();
        assertThat(n.publicationId()).isEqualTo(notificationId);
        assertThat(n.operationId()).isEqualTo(notificationId);
        assertThat(n.leaseEpoch()).isEqualTo(1L);
        assertThat(n.templateVersion()).isEqualTo("duty-v1");
        String state = adminJdbc.sql(
                "SELECT state FROM duty_delivery WHERE notification_id = :nid")
                .param("nid", notificationId).query(String.class).single();
        assertThat(state).isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("markSent → SENT+sent_at（lifecycle CHECK 成立）；陈旧 epoch 拒绝")
    void markSentAndStaleEpochFence() {
        UUID notificationId = seedDutyPair();
        ClaimedNotification n = claimMine(notificationId);

        adapter.markSent(n.id(), n.leaseEpoch(), java.time.Instant.now());
        String[] row = adminJdbc.sql("""
                SELECT state, sent_at IS NOT NULL FROM duty_delivery WHERE id = :id
                """).param("id", n.id()).query((rs, i) ->
                new String[]{rs.getString(1), String.valueOf(rs.getBoolean(2))}).single();
        assertThat(row[0]).isEqualTo("SENT");
        assertThat(row[1]).isEqualTo("true");

        // 陈旧 epoch（僵尸 worker 晚到回写）：0 行 → StaleClaimException
        assertThatThrownBy(() -> adapter.markSent(n.id(), n.leaseEpoch() - 1,
                java.time.Instant.now())).isInstanceOf(StaleClaimException.class);
    }

    @Test
    @DisplayName("markRetryWait：429 不耗预算（bump=0）与 5xx 耗预算（bump=1）两态（B-55②：两次尝试=两个领取周期）")
    void markRetryWaitConsumesAttemptOnlyWhenTold() {
        UUID notificationId = seedDutyPair();
        ClaimedNotification n = claimMine(notificationId);

        // 尝试 1：429 限流——bump=0 不耗预算；available_at 置过去使行立刻可再领取
        adapter.markRetryWait(n.id(), n.leaseEpoch(),
                Instant.now().minusSeconds(1), false,
                "{\"reason\":\"rate_limited\"}");
        int attempts = adminJdbc.sql(
                "SELECT attempt_count FROM duty_delivery WHERE id = :id")
                .param("id", n.id()).query(Integer.class).single();
        assertThat(attempts).isZero();

        // 单次领取只许一次 mark（CLAIMED 栅栏）；尝试 2 = 行回 RETRY_WAIT 后再领取
        ClaimedNotification n2 = claimMine(notificationId);
        assertThat(n2.leaseEpoch()).isEqualTo(n.leaseEpoch() + 1);

        // 尝试 2：5xx——bump=1 耗预算
        adapter.markRetryWait(n2.id(), n2.leaseEpoch(),
                Instant.now().plusSeconds(60), true,
                "{\"reason\":\"http_500\"}");
        attempts = adminJdbc.sql("SELECT attempt_count FROM duty_delivery WHERE id = :id")
                .param("id", n2.id()).query(Integer.class).single();
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("授权：notify_app 不得 INSERT duty_delivery（补投行=control_app watcher 单写者）、duty_notification 只读")
    void notifyAppCannotInsertDeliveryOrTouchNotification() {
        UUID notificationId = seedDutyPair();
        UUID channelId = adminJdbc.sql(
                "SELECT channel_id FROM duty_delivery WHERE notification_id = :nid")
                .param("nid", notificationId).query(UUID.class).single();

        boolean denied = false;
        try {
            notifyJdbc.sql("""
                    INSERT INTO duty_delivery(id, notification_id, channel_id, priority, state,
                        lease_epoch, attempt_count, max_attempts, available_at,
                        created_at, updated_at)
                    VALUES (:id, :nid, :cid, 9, 'PENDING', 0, 0, 5, now(), now(), now())
                    """).param("id", UUID.randomUUID()).param("nid", notificationId)
                    .param("cid", channelId).update();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            denied = chain.toString().contains("permission denied");
        }
        assertThat(denied).as("notify_app INSERT duty_delivery 必须被拒").isTrue();

        denied = false;
        try {
            notifyJdbc.sql("UPDATE duty_notification SET title = 'x' WHERE false").update();
        } catch (RuntimeException e) {
            denied = String.valueOf(e.getMessage()).contains("permission denied")
                    || (e.getCause() != null
                    && String.valueOf(e.getCause().getMessage()).contains("permission denied"));
        }
        assertThat(denied).as("notify_app 对 duty_notification 只读").isTrue();
    }
}
