package com.objwww.pr.control.ops.duty.application;

import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-13 派发服务锚点（AM7 §11）：原子对落库、快照解析→首通道、重复告警去重、
 * 空链诚实记账（结构化事件不静默）、MANUAL 测试入口。假件 store 钉调用形状；
 * 真 PG 事务/约束行为归 195 IT。
 */
class DutyDispatchServiceTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW =
            LocalDate.of(2026, 9, 8).atTime(LocalTime.of(10, 0)).atZone(CST).toInstant();

    private static DutyScheduleSnapshot.Channel chan(int priority) {
        return new DutyScheduleSnapshot.Channel(UUID.randomUUID(), "bot-" + priority,
                "DINGTALK", "K_" + priority, null, priority, priority == 9, true);
    }

    /** 假件：记录调用 + 可配置快照/撞行行为 */
    static final class FakeStore implements DutyStore {
        final List<NewNotification> notifications = new ArrayList<>();
        final List<Map.Entry<UUID, DutyScheduleSnapshot.Channel>> deliveries = new ArrayList<>();
        DutyScheduleSnapshot snapshot = snapshot(List.of(), List.of(), List.of(chan(1)));
        UUID existingId;
        final AtomicLong seq = new AtomicLong();

        @Override
        public DutyScheduleSnapshot loadSnapshot() {
            return snapshot;
        }

        @Override
        public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                                   DutyScheduleSnapshot.Channel c) {
            notifications.add(n);
            if (existingId != null) {
                return new DispatchOutcome(existingId, false, c == null ? null : c.name());
            }
            UUID id = UUID.nameUUIDFromBytes(("n" + seq.incrementAndGet()).getBytes());
            if (c != null) {
                deliveries.add(Map.entry(id, c));
            }
            return new DispatchOutcome(id, true, c == null ? null : c.name());
        }

        @Override
        public DispatchOutcome insertExternalNotification(NewNotification n) {
            notifications.add(n);
            if (existingId != null) {
                return new DispatchOutcome(existingId, false, null);
            }
            return new DispatchOutcome(
                    UUID.nameUUIDFromBytes(("e" + seq.incrementAndGet()).getBytes()), true, null);
        }

        @Override
        public boolean insertDeliveryIfAbsent(UUID notificationId, DutyScheduleSnapshot.Channel c) {
            deliveries.add(Map.entry(notificationId, c));
            return true;
        }

        @Override
        public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
            return List.of();
        }

        @Override
        public boolean markRead(UUID notificationId, Instant readAt) {
            return true;
        }

        @Override
        public List<DutyNotificationView> listNotifications(String cursor, int limit,
                                                             boolean unreadOnly) {
            return List.of();
        }

        @Override
        public long unreadCount() {
            return 0;
        }

        @Override
        public List<NotificationFeedView> listFeed(String cursor, int limit) {
            return List.of();
        }

        @Override
        public Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> notificationIds) {
            return Map.of();
        }

        static DutyScheduleSnapshot snapshot(List<DutyScheduleSnapshot.Layer> layers,
                                             List<DutyScheduleSnapshot.Override> overrides,
                                             List<DutyScheduleSnapshot.Channel> channels) {
            return new DutyScheduleSnapshot(UUID.randomUUID(), "primary", 1L, NOW.minusSeconds(5),
                    NOW.plusSeconds(60), CST, com.objwww.pr.control.ops.duty.domain.RotationMath.Rotation.DAILY,
                    LocalDate.of(2026, 9, 7), LocalTime.of(9, 0), layers, overrides, channels);
        }
    }

    private static com.objwww.pr.control.alert.domain.model.AlertGroupSummary summary(
            String groupKey, String status, Map<String, String> labels, Instant startsAt,
            List<String> alertnames) {
        return new com.objwww.pr.control.alert.domain.model.AlertGroupSummary(
                "control", groupKey, status, labels, alertnames, startsAt, 1);
    }

    @Test
    void dispatchesToHighestPriorityChannelWithEpisodeIdentity() {
        FakeStore store = new FakeStore();
        store.snapshot = FakeStore.snapshot(List.of(
                        new DutyScheduleSnapshot.Layer(0, List.of("alice"))),
                List.of(), List.of(chan(2), chan(1)));
        DutyDispatchService svc = new DutyDispatchService(store, () -> NOW);

        DutyStore.DispatchOutcome out = svc.dispatchSystem(summary("gk-1", "firing",
                Map.of("alertname", "RCA_SYSTEM_LLM", "instance", "litellm-1"),
                NOW.minus(Duration.ofMinutes(3)), List.of("RCA_SYSTEM_LLM")));

        assertThat(out.created()).isTrue();
        assertThat(out.channelName()).isEqualTo("bot-1");
        DutyStore.NewNotification saved = store.notifications.get(0);
        assertThat(saved.source()).isEqualTo("RCA_SYSTEM");
        assertThat(saved.eventStatus()).isEqualTo("firing");
        assertThat(saved.title()).contains("RCA_SYSTEM_LLM");
        // 契约①：不同 instance → 不同 alertFingerprint；firing/resolved → 不同 fingerprint
        assertThat(saved.alertFingerprint()).hasSize(64);
        assertThat(saved.episodeId()).hasSize(64);
    }

    @Test
    void duplicateGroupDedupsByFingerprintNotError() {
        FakeStore store = new FakeStore();
        store.existingId = UUID.randomUUID();
        DutyDispatchService svc = new DutyDispatchService(store, () -> NOW);

        var g = summary("gk-1", "firing", Map.of("alertname", "RCA_SYSTEM_X"),
                NOW.minusSeconds(60), List.of("RCA_SYSTEM_X"));
        DutyStore.DispatchOutcome out = svc.dispatchSystem(g);

        assertThat(out.created()).isFalse();
        assertThat(out.notificationId()).isEqualTo(store.existingId);
    }

    @Test
    void resolvedIsADistinctRowFromFiring() {
        // 契约①：同 episode 的 resolved 不被 firing 去重——身份三段里 eventStatus 参与 fingerprint
        var firing = com.objwww.pr.control.ops.duty.domain.DutyAlertIdentity.of("RCA_SYSTEM",
                Map.of("alertname", "A"), "gk", NOW, "firing");
        var resolved = com.objwww.pr.control.ops.duty.domain.DutyAlertIdentity.of("RCA_SYSTEM",
                Map.of("alertname", "A"), "gk", NOW, "resolved");
        assertThat(firing.alertFingerprint()).isEqualTo(resolved.alertFingerprint());
        assertThat(firing.episodeId()).isEqualTo(resolved.episodeId());
        assertThat(firing.fingerprint()).isNotEqualTo(resolved.fingerprint());
    }

    @Test
    void emptyChannelChainRecordsNotificationOnly() {
        // 不变量 4 诚实面：无通道可投（连 fallback 都没配）→ 通知行照落（台账），
        // 零投递行 + 显式结局 channelName=null——不静默丢弃
        FakeStore store = new FakeStore();
        store.snapshot = FakeStore.snapshot(List.of(), List.of(), List.of());
        DutyDispatchService svc = new DutyDispatchService(store, () -> NOW);

        DutyStore.DispatchOutcome out = svc.dispatchSystem(summary("gk-2", "firing",
                Map.of("alertname", "RCA_SYSTEM_Y"), NOW.minusSeconds(30),
                List.of("RCA_SYSTEM_Y")));

        assertThat(out.created()).isTrue();
        assertThat(out.channelName()).isNull();
        assertThat(store.notifications).hasSize(1);
        assertThat(store.deliveries).isEmpty();
    }

    @Test
    void manualTestEntryAlwaysCreatesFreshEpisode() {
        FakeStore store = new FakeStore();
        DutyDispatchService svc = new DutyDispatchService(store, () -> NOW);

        DutyStore.DispatchOutcome a = svc.dispatchManual("测试通知", "手动测试通道链", "P3");
        DutyStore.DispatchOutcome b = svc.dispatchManual("测试通知", "手动测试通道链", "P3");

        assertThat(a.created()).isTrue();
        assertThat(b.created()).isTrue();
        assertThat(a.notificationId()).isNotEqualTo(b.notificationId());
        assertThat(store.notifications).hasSize(2);
        assertThat(store.notifications.get(0).source()).isEqualTo("MANUAL");
        assertThat(store.notifications.get(0).severity()).isEqualTo("P3");
    }
}
