package com.objwww.pr.control.ops.duty.application;

import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-13 降级 watcher 锚点（AM7 §11 + 评审契约③）：DEAD → 次优先级补投、
 * 期限外不降级、链耗尽记台账事件、空窗口空转。
 */
class DutyFallbackWatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-08T02:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private static DutyScheduleSnapshot.Channel chan(int priority) {
        return new DutyScheduleSnapshot.Channel(UUID.randomUUID(), "bot-" + priority,
                "DINGTALK", "K_" + priority, null, priority, priority == 9, true);
    }

    static final class FakeStore implements DutyStore {
        final List<DutyStore.DeadDelivery> dead = new ArrayList<>();
        final List<Map.Entry<UUID, DutyScheduleSnapshot.Channel>> inserted = new ArrayList<>();
        DutyScheduleSnapshot snapshot;

        @Override
        public DutyScheduleSnapshot loadSnapshot() {
            return snapshot;
        }

        @Override
        public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                                   DutyScheduleSnapshot.Channel c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DispatchOutcome insertExternalNotification(NewNotification n) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean insertDeliveryIfAbsent(UUID notificationId, DutyScheduleSnapshot.Channel c) {
            inserted.add(Map.entry(notificationId, c));
            return true;
        }

        @Override
        public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
            return dead;
        }

        @Override
        public boolean markRead(UUID notificationId, Instant readAt) {
            return false;
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
    }

    private DutyFallbackWatcher watcher(FakeStore store) {
        return new DutyFallbackWatcher(store, () -> NOW, MAX_AGE);
    }

    @Test
    void deadDeliveryDegradesToNextPriorityChannel() {
        FakeStore store = new FakeStore();
        // 有人当班 → 完整通道链（1,2）都是活路；无成员会走 fallback-only 分支
        store.snapshot = DutyDispatchServiceTest.FakeStore.snapshot(
                List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice"))), List.of(),
                List.of(chan(2), chan(1)));
        UUID nid = UUID.randomUUID();
        store.dead.add(new DutyStore.DeadDelivery(nid, UUID.randomUUID(), 1,
                NOW.minus(Duration.ofMinutes(5))));

        int acted = watcher(store).runOnce();

        assertThat(acted).isEqualTo(1);
        assertThat(store.inserted).hasSize(1);
        assertThat(store.inserted.get(0).getValue().priority()).isEqualTo(2);
        assertThat(store.inserted.get(0).getKey()).isEqualTo(nid);
    }

    @Test
    void deadlineExceededNotificationIsNotDegraded() {
        // 契约③ 最长通知期限：期限外的 DEAD 不再补投（不无限降级）
        FakeStore store = new FakeStore();
        store.snapshot = DutyDispatchServiceTest.FakeStore.snapshot(List.of(), List.of(),
                List.of(chan(1), chan(2)));
        store.dead.add(new DutyStore.DeadDelivery(UUID.randomUUID(), UUID.randomUUID(), 1,
                NOW.minus(MAX_AGE).minusSeconds(1)));

        assertThat(watcher(store).runOnce()).isZero();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    void exhaustedChainRecordsLedgerEventWithoutInsert() {
        // 不变量 4：无下一通道 → SUPPRESSED 台账（结构化事件），不插行不静默
        FakeStore store = new FakeStore();
        store.snapshot = DutyDispatchServiceTest.FakeStore.snapshot(List.of(), List.of(),
                List.of(chan(1)));
        store.dead.add(new DutyStore.DeadDelivery(UUID.randomUUID(), UUID.randomUUID(), 1,
                NOW.minus(Duration.ofMinutes(1))));

        assertThat(watcher(store).runOnce()).isZero();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    void emptyScanWindowIsNoOp() {
        FakeStore store = new FakeStore();
        store.snapshot = DutyDispatchServiceTest.FakeStore.snapshot(List.of(), List.of(),
                List.of(chan(1), chan(2)));
        assertThat(watcher(store).runOnce()).isZero();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    void fallbackChannelServesAsLastDegradationTarget() {
        // fallback（priority 最大）在优先级序里天然是降级链末环——空解析的起点、
        // 链耗尽前的最后活路，同一排序原则不搞特例
        FakeStore store = new FakeStore();
        store.snapshot = DutyDispatchServiceTest.FakeStore.snapshot(List.of(), List.of(),
                List.of(chan(1), chan(9)));
        store.dead.add(new DutyStore.DeadDelivery(UUID.randomUUID(), UUID.randomUUID(), 1,
                NOW.minus(Duration.ofMinutes(5))));

        assertThat(watcher(store).runOnce()).isEqualTo(1);
        assertThat(store.inserted.get(0).getValue().priority()).isEqualTo(9);
    }
}
