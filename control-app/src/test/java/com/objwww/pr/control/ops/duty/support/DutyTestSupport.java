package com.objwww.pr.control.ops.duty.support;

import com.objwww.pr.control.alert.domain.model.AlertGroupSummary;
import com.objwww.pr.control.ops.duty.application.DutyDispatchService;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.duty.domain.DutyStore.DispatchOutcome;
import com.objwww.pr.control.ops.duty.domain.RotationMath;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 值班面测试支撑（M7-13）：空快照 store + 记录型派发服务——controller/IT 复用
 * （alert.interfaces 测试跨包用，公共支撑放 ops.duty.support）。
 */
public final class DutyTestSupport {

    private DutyTestSupport() {
    }

    public static DutyScheduleSnapshot emptySnapshot() {
        Instant now = Instant.now();
        return new DutyScheduleSnapshot(UUID.randomUUID(), "(none)", 0L, now,
                now.plusSeconds(90), ZoneId.of("Asia/Shanghai"),
                RotationMath.Rotation.DAILY, LocalDate.EPOCH, LocalTime.MIDNIGHT,
                List.of(), List.of(), List.of());
    }

    /** 全空实现 store（loadSnapshot=空快照——空解析、零通道、零降级） */
    public static DutyStore emptyStore() {
        return new DutyStore() {
            @Override
            public DutyScheduleSnapshot loadSnapshot() {
                return emptySnapshot();
            }

            @Override
            public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                                       DutyScheduleSnapshot.Channel c) {
                return new DispatchOutcome(UUID.randomUUID(), true, c == null ? null : c.name());
            }

            @Override
            public DispatchOutcome insertExternalNotification(NewNotification n) {
                return new DispatchOutcome(UUID.randomUUID(), true, null);
            }

            @Override
            public boolean insertDeliveryIfAbsent(UUID notificationId,
                                                  DutyScheduleSnapshot.Channel channel) {
                return false;
            }

            @Override
            public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
                return List.of();
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
        };
    }

    /** 记录型派发：捕获 dispatchSystem 入参，回新造结局（不触库） */
    public static final class RecordingDispatch extends DutyDispatchService {

        public final List<AlertGroupSummary> groups = new ArrayList<>();

        public RecordingDispatch() {
            super(emptyStore(), Instant::now);
        }

        @Override
        public DispatchOutcome dispatchSystem(AlertGroupSummary group) {
            groups.add(group);
            return new DispatchOutcome(UUID.randomUUID(), true, "recorder");
        }
    }
}
