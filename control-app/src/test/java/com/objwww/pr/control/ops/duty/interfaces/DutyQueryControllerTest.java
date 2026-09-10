package com.objwww.pr.control.ops.duty.interfaces;

import com.objwww.pr.control.ops.duty.application.DutyDispatchService;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.duty.domain.RotationMath;
import com.objwww.pr.control.ops.duty.support.DutyTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7-15 查询/测试面锚点：游标分页形状、已读幂等（契约④——只翻 status）、
 * 快照契约⑤字段（version/validUntil/onCall）、MANUAL 测试入口。
 * 授权矩阵归 SecurityConfigTest（C3a 链级）；本面 standalone 只验 controller 语义。
 */
class DutyQueryControllerTest {

    /** 可编程假件：列表/已读行为可断言 */
    static final class ProgrammableStore implements DutyStore {
        final List<DutyNotificationView> rows = new ArrayList<>();
        final List<UUID> readCalls = new ArrayList<>();
        boolean readResult = true;
        DutyScheduleSnapshot snapshot = DutyTestSupport.emptySnapshot();

        @Override
        public DutyScheduleSnapshot loadSnapshot() {
            return snapshot;
        }

        @Override
        public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                                   DutyScheduleSnapshot.Channel c) {
            return new DispatchOutcome(UUID.randomUUID(), true, c == null ? null : c.name());
        }

        final List<NewNotification> externalInserts = new ArrayList<>();

        @Override
        public DispatchOutcome insertExternalNotification(NewNotification n) {
            externalInserts.add(n);
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
            readCalls.add(notificationId);
            return readResult;
        }

        @Override
        public List<DutyNotificationView> listNotifications(String cursor, int limit,
                                                             boolean unreadOnly) {
            return rows.subList(0, Math.min(limit, rows.size()));
        }

        @Override
        public long unreadCount() {
            return rows.size();
        }

        final List<NotificationFeedView> feedRows = new ArrayList<>();
        final Map<UUID, List<DeliveryView>> deliveryMap = new LinkedHashMap<>();

        @Override
        public List<NotificationFeedView> listFeed(String cursor, int limit) {
            return feedRows.subList(0, Math.min(limit, feedRows.size()));
        }

        @Override
        public Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> notificationIds) {
            Map<UUID, List<DeliveryView>> out = new LinkedHashMap<>();
            for (UUID id : notificationIds) {
                out.put(id, deliveryMap.getOrDefault(id, List.of()));
            }
            return out;
        }
    }

    private ProgrammableStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new ProgrammableStore();
        DutyDispatchService dispatch = new DutyDispatchService(store, Instant::now);
        mvc = MockMvcBuilders.standaloneSetup(new DutyQueryController(store, dispatch)).build();
    }

    @Test
    void notificationsListCarriesCursorAndUnreadCount() throws Exception {
        Instant base = Instant.parse("2026-09-08T02:00:00Z");
        for (int i = 0; i < 3; i++) {
            store.rows.add(new DutyStore.DutyNotificationView(UUID.randomUUID(), "RCA_SYSTEM",
                    "ep", "firing", "P2", "title-" + i, "UNREAD", base.minusSeconds(i * 60)));
        }

        mvc.perform(get("/api/duty/notifications").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(2))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void feedCarriesBubbleShapeAndRealDeliveryState() throws Exception {
        Instant base = Instant.parse("2026-09-10T01:00:00Z");
        UUID withDelivery = UUID.randomUUID();
        UUID gatusDirect = UUID.randomUUID();
        store.feedRows.add(new DutyStore.NotificationFeedView(withDelivery, "RCA_SYSTEM",
                "ep-1", "firing", "P2", "CPU 飙高", "正文摘要", "UNREAD", base));
        store.feedRows.add(new DutyStore.NotificationFeedView(gatusDirect, "GATUS",
                "ep-2", "resolved", null, "探针恢复", "恢复正文", "UNREAD",
                base.minusSeconds(60)));
        store.deliveryMap.put(withDelivery, List.of(
                new DutyStore.DeliveryView("dead-bot", "WECOM", 1, "DEAD", 5, null,
                        "{\"error\":\"http_500\"}", false),
                new DutyStore.DeliveryView("echo-bot", "WECOM", 2, "SENT", 0,
                        base.plusSeconds(465), null, true)));

        mvc.perform(get("/api/duty/notifications/feed").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].title").value("CPU 飙高"))
                .andExpect(jsonPath("$.messages[0].body").value("正文摘要"))
                // 降级链真实两行：DEAD 在前（priority 1）、补投 SENT 在后，演练标记随行
                .andExpect(jsonPath("$.messages[0].deliveries[0].channel").value("dead-bot"))
                .andExpect(jsonPath("$.messages[0].deliveries[0].state").value("DEAD"))
                .andExpect(jsonPath("$.messages[0].deliveries[0].drill").value(false))
                .andExpect(jsonPath("$.messages[0].deliveries[1].channel").value("echo-bot"))
                .andExpect(jsonPath("$.messages[0].deliveries[1].state").value("SENT"))
                .andExpect(jsonPath("$.messages[0].deliveries[1].drill").value(true))
                // GATUS 腿 127 直发回写——195 无投递行，空列表如实（前端标注直发）
                .andExpect(jsonPath("$.messages[1].source").value("GATUS"))
                .andExpect(jsonPath("$.messages[1].deliveries.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void markReadIsIdempotentAndDistinguishesMissingRow() throws Exception {
        UUID id = UUID.randomUUID();
        // 行在场但已读（CAS 0 行）→ 幂等 200 changed:false（契约④：只翻 status，不产接单）
        store.rows.add(new DutyStore.DutyNotificationView(id, "MANUAL", "ep", "firing",
                null, "t", "READ", Instant.now()));
        store.readResult = false;
        mvc.perform(post("/api/duty/notifications/{id}/read", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
        assertThat(store.readCalls).containsExactly(id);

        store.rows.clear();   // 行不存在 → 404
        mvc.perform(post("/api/duty/notifications/{id}/read", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotCarriesStalenessContractFields() throws Exception {
        Instant now = Instant.now();
        store.snapshot = new DutyScheduleSnapshot(UUID.randomUUID(), "primary", 7L,
                now.minusSeconds(5), now.plusSeconds(85), ZoneId.of("Asia/Shanghai"),
                RotationMath.Rotation.WEEKLY, LocalDate.of(2026, 9, 7),
                LocalTime.of(9, 0),
                List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice", "bob"))),
                List.of(),
                List.of(new DutyScheduleSnapshot.Channel(UUID.randomUUID(), "primary-bot",
                        "DINGTALK", "K_X", null, 1, false, true)));

        mvc.perform(get("/api/duty/schedule/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleVersion").value(7))
                .andExpect(jsonPath("$.validUntil").isNotEmpty())
                .andExpect(jsonPath("$.onCall").isNotEmpty())
                .andExpect(jsonPath("$.channels[0].name").value("primary-bot"))
                .andExpect(jsonPath("$.channels[0].priority").value(1))
                // 127 adapter 读 env 的键名面（M7-17；键名非密钥值）
                .andExpect(jsonPath("$.channels[0].envKeyWebhook").value("K_X"));
    }

    @Test
    void gatusWriteBackInsertsLedgerRowWithoutDelivery() throws Exception {
        mvc.perform(post("/api/duty/notifications")
                        .contentType("application/json")
                        .content("""
                                {"eventStatus":"firing","title":"探针告警 RCA_SYSTEM_control_health",
                                 "body":"3 连败","groupKey":"gatus/control-plane",
                                 "triggeredAt":"2026-09-10T08:00:00Z",
                                 "labels":{"endpoint":"RCA_SYSTEM_control_health",
                                           "group":"control-plane","severity":"critical"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.notificationId").isNotEmpty());

        assertThat(store.externalInserts).hasSize(1);
        DutyStore.NewNotification saved = store.externalInserts.get(0);
        assertThat(saved.source()).isEqualTo("GATUS");
        assertThat(saved.eventStatus()).isEqualTo("firing");
        assertThat(saved.alertFingerprint()).hasSize(64);
        assertThat(saved.payloadJson()).contains("\"operation_id\"");

        // 非法 eventStatus / 缺 triggeredAt → 400（adapter 侧契约钉）
        mvc.perform(post("/api/duty/notifications")
                        .contentType("application/json")
                        .content("{\"eventStatus\":\"unknown\",\"title\":\"t\",\"body\":\"b\","
                                + "\"triggeredAt\":\"2026-09-10T08:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/duty/notifications")
                        .contentType("application/json")
                        .content("{\"eventStatus\":\"firing\",\"title\":\"t\",\"body\":\"b\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testNotificationValidatesAndDispatches() throws Exception {
        mvc.perform(post("/api/duty/test-notification")
                        .contentType("application/json")
                        .content("{\"title\":\"测试\",\"body\":\"手动测试\",\"severity\":\"P3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.notificationId").isNotEmpty());

        mvc.perform(post("/api/duty/test-notification")
                        .contentType("application/json")
                        .content("{\"body\":\"缺 title\"}"))
                .andExpect(status().isBadRequest());
    }
}
