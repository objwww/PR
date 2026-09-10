package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresDutyStore;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V43 duty 八表 + {@link PostgresDutyStore} 真 PG 组件测试（B-57 守卫）。
 *
 * <p>B-57（195 首跑 L2 暴露）：loadSnapshot 的 layers 查询漏了 JdbcClient 终结操作
 * （.query(mapper) 无 .list() = 死语句，SQL 不执行）——本地 UT 用假 store 不可见、
 * 编译器不报、195 真 PG 才显形（快照 layers/channels 恒空 → onCall 恒 null →
 * 派发恒 DUTY_DISPATCH_NO_CHANNEL）。本 IT 以真库钉死 loadSnapshot 全链，
 * 是该缺陷的 L1 回归锚。
 */
class PostgresDutyStoreIT extends PostgresITBase {

    private final PostgresDutyStore store = new PostgresDutyStore(controlJdbc, controlTx);

    /** 种子：两成员/两通道（P1 主 + P9 fallback）/WEEKLY 排班 + 首层 [alice,bob] */
    private UUID seedDutyConfig() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID layerId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO duty_member(id, name, display_name, active, created_at, updated_at)
                VALUES (:id,'alice','值班甲',true,now(),now()), (:id2,'bob','值班乙',true,now(),now())
                """).param("id", alice).param("id2", bob).update();
        adminJdbc.sql("""
                INSERT INTO duty_channel(id, name, platform, env_key_webhook, env_key_secret,
                    priority, is_fallback, enabled, created_at, updated_at)
                VALUES (:id,'duty-primary','DINGTALK','K_DUTY_TEST_WEBHOOK',null,1,false,true,now(),now()),
                       (:id2,'duty-emergency','WECOM','K_DUTY_EMERGENCY_WEBHOOK',null,9,true,true,now(),now())
                """).param("id", UUID.randomUUID()).param("id2", UUID.randomUUID()).update();
        adminJdbc.sql("""
                INSERT INTO duty_schedule(id, name, timezone, rotation, anchor_date, handoff_time,
                    schedule_version, created_at, updated_at)
                VALUES (:id,'primary','Asia/Shanghai','WEEKLY','2026-09-07','09:00',7,now(),now())
                """).param("id", scheduleId).update();
        adminJdbc.sql("""
                INSERT INTO duty_layer(id, schedule_id, layer_index, created_at)
                VALUES (:id, :sid, 0, now())
                """).param("id", layerId).param("sid", scheduleId).update();
        adminJdbc.sql("""
                INSERT INTO duty_layer_member(layer_id, member_id, position)
                VALUES (:lid, :alice, 0), (:lid, :bob, 1)
                """).param("lid", layerId).param("alice", alice).param("bob", bob).update();
        return scheduleId;
    }

    @Test
    @DisplayName("loadSnapshot 全链：排班/层成员（B-57 死语句锚）/通道按优先级/版本（红→绿的判据就是本断言）")
    void loadSnapshotReturnsLayersAndChannels() {
        seedDutyConfig();

        DutyScheduleSnapshot snapshot = store.loadSnapshot();

        assertThat(snapshot.name()).isEqualTo("primary");
        assertThat(snapshot.scheduleVersion()).isEqualTo(7L);
        assertThat(snapshot.zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(snapshot.anchorDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(snapshot.handoffTime()).isEqualTo(LocalTime.of(9, 0));
        // B-57 主锚：漏 .list() 时 layers 恒空——真库两成员必须都在
        assertThat(snapshot.layers()).hasSize(1);
        assertThat(snapshot.layers().get(0).memberNames()).containsExactly("alice", "bob");
        assertThat(snapshot.channels()).hasSize(2);
        assertThat(snapshot.channels()).extracting(DutyScheduleSnapshot.Channel::name)
                .containsExactly("duty-primary", "duty-emergency"); // priority 1 < 9
        assertThat(snapshot.channels().get(0).envKeyWebhook()).isEqualTo("K_DUTY_TEST_WEBHOOK");
        assertThat(snapshot.overrides()).isEmpty();
    }

    @Test
    @DisplayName("resolver 接真快照：WEEKLY anchor+1 天 → position 0 → alice 当班；override 窗口内 → bob 顶班")
    void resolverPicksOnCallMemberFromRealSnapshot() {
        seedDutyConfig();
        Instant dutyTime = LocalDateTime.of(2026, 9, 8, 10, 0)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        DutyResolver.Resolution resolved = DutyResolver.resolve(dutyTime, store.loadSnapshot());
        assertThat(resolved.onCall()).isEqualTo("alice");
        assertThat(resolved.viaFallback()).isFalse();
        assertThat(resolved.channelChain()).extracting(DutyScheduleSnapshot.Channel::name)
                .containsExactly("duty-primary", "duty-emergency");

        // override：起点取解析时刻之前的绝对时间（含 dutyTime），终点相对 now()+1d
        // （loadSnapshot 只加载 ends_at > now() 的未过期 override——两端约束都要满足）
        UUID scheduleId = store.loadSnapshot().scheduleId();
        adminJdbc.sql("""
                INSERT INTO duty_override(id, schedule_id, member_id, starts_at, ends_at, reason, created_at)
                VALUES (:id, :sid, (SELECT id FROM duty_member WHERE name='bob'),
                    timestamptz '2026-09-08 01:00+08', now() + interval '1 day', 'it-override', now())
                """).param("id", UUID.randomUUID()).param("sid", scheduleId).update();
        DutyResolver.Resolution overridden = DutyResolver.resolve(dutyTime, store.loadSnapshot());
        assertThat(overridden.onCall()).isEqualTo("bob");
        assertThat(overridden.viaOverride()).isTrue();
    }

    @Test
    @DisplayName("派发原子对：通知+首投行同事务落库；fingerprint 重发去重不铸第二行")
    void dispatchInsertsNotificationWithFirstDeliveryAndDedups() {
        seedDutyConfig();
        DutyScheduleSnapshot snapshot = store.loadSnapshot();
        DutyScheduleSnapshot.Channel p1 = snapshot.channels().get(0);
        DutyStore.NewNotification n = new DutyStore.NewNotification(
                "RCA_SYSTEM", "ep-1", "afp-1", "firing", "P2", "t", "b",
                "{\"type\":\"duty\"}", "fp-1");

        DutyStore.DispatchOutcome first = store.insertNotificationWithFirstDelivery(n, p1);
        assertThat(first.created()).isTrue();
        assertThat(first.channelName()).isEqualTo("duty-primary");

        DutyStore.DispatchOutcome resend = store.insertNotificationWithFirstDelivery(n, p1);
        assertThat(resend.created()).isFalse();
        assertThat(resend.notificationId()).isEqualTo(first.notificationId());

        assertThat(count("duty_notification")).isEqualTo(1);
        assertThat(count("duty_delivery")).isEqualTo(1); // (notification_id, priority) 唯一
        String[] delivery = adminJdbc.sql(
                "SELECT state, priority FROM duty_delivery").query((rs, i) ->
                new String[]{rs.getString(1), String.valueOf(rs.getInt(2))}).single();
        assertThat(delivery[0]).isEqualTo("PENDING");
        assertThat(delivery[1]).isEqualTo("1");
    }

    @Test
    @DisplayName("空链派发=只落通知行（首启未配通道的诚实面）+ 已读 CAS 只翻一次")
    void nullChannelStoresNotificationOnlyAndMarkReadFlipsOnce() {
        seedDutyConfig();
        DutyStore.NewNotification n = new DutyStore.NewNotification(
                "RCA_SYSTEM", "ep-2", "afp-2", "firing", "P2", "t", "b",
                "{\"type\":\"duty\"}", "fp-2");
        DutyStore.DispatchOutcome out = store.insertNotificationWithFirstDelivery(n, null);
        assertThat(out.created()).isTrue();
        assertThat(out.channelName()).isNull();
        assertThat(count("duty_notification")).isEqualTo(1);
        assertThat(count("duty_delivery")).isZero(); // 无通道=无投递行（不伪造）

        assertThat(store.markRead(out.notificationId(), Instant.now())).isTrue();
        assertThat(store.markRead(out.notificationId(), Instant.now())).isFalse(); // 幂等
        assertThat(store.unreadCount()).isZero();
        List<DutyStore.DutyNotificationView> rows = store.listNotifications(null, 10, false);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("READ");
    }

    @Test
    @DisplayName("群模拟 feed（EX-D1）：通知行带正文+真实投递行；演练通道标记随行"
            + "（种子通道 env 键名含 TEST→drill=true）；无投递行的通知空组如实")
    void feedCarriesBodyAndRealDeliveryRows() {
        seedDutyConfig();
        DutyScheduleSnapshot snapshot = store.loadSnapshot();
        DutyStore.NewNotification n = new DutyStore.NewNotification(
                "RCA_SYSTEM", "ep-f1", "afp-f1", "firing", "P2", "feed-t", "feed-正文",
                "{\"type\":\"duty\"}", "fp-f1");
        DutyStore.DispatchOutcome out = store.insertNotificationWithFirstDelivery(
                n, snapshot.channels().get(0));
        DutyStore.NewNotification bare = new DutyStore.NewNotification(
                "GATUS", "ep-f2", "afp-f2", "resolved", null, "feed-t2", "feed-正文2",
                null, "fp-f2");
        DutyStore.DispatchOutcome bareOut = store.insertNotificationWithFirstDelivery(bare, null);

        List<DutyStore.NotificationFeedView> feed = store.listFeed(null, 10);
        assertThat(feed).hasSize(2);
        DutyStore.NotificationFeedView first = feed.stream()
                .filter(v -> v.id().equals(out.notificationId())).findFirst().orElseThrow();
        assertThat(first.bodyText()).isEqualTo("feed-正文");

        Map<UUID, List<DutyStore.DeliveryView>> deliveries = store.listDeliveries(
                feed.stream().map(DutyStore.NotificationFeedView::id).toList());
        List<DutyStore.DeliveryView> d1 = deliveries.get(out.notificationId());
        assertThat(d1).hasSize(1);
        assertThat(d1.get(0).channelName()).isEqualTo("duty-primary");
        assertThat(d1.get(0).state()).isEqualTo("PENDING");
        assertThat(d1.get(0).drill()).isTrue(); // K_DUTY_TEST_WEBHOOK 键名含 TEST
        assertThat(deliveries.getOrDefault(bareOut.notificationId(), List.of())).isEmpty();

        // 空入参短路（聊天页空列表首刷不打 SQL）
        assertThat(store.listDeliveries(List.of())).isEmpty();
    }
}
