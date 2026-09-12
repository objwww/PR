package com.objwww.pr.control.ops.dutybot.application;

import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.duty.domain.RotationMath;
import com.objwww.pr.control.ops.dutybot.domain.DutyBotStore;
import com.objwww.pr.control.ops.dutybot.domain.NotifyStatusReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DutyBotService 单测（UX-02；假端口 + 计数事务直通）：
 * 一问一答同事务双落库、各意图真实数据回复、无数据诚实"查询无结果"、
 * 越权隔离（非本人 404 面）、长度/条数/频率上限零副作用、clientMessageId
 * 幂等重放、游标分页。
 */
class DutyBotServiceTest {

    /** 2026-09-11 10:00 +08（anchor 2026-09-01 09:00 起 10 天 → position=0=alice） */
    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OWNER = "op-a";

    private final InMemoryDutyBotStore store = new InMemoryDutyBotStore();
    private final FakeDutyStore duty = new FakeDutyStore();
    private final FakeIncidentReader incidents = new FakeIncidentReader();
    private final FakeNotifyReader notify = new FakeNotifyReader();
    private final AtomicInteger txCount = new AtomicInteger();
    private final MutableClock clock = new MutableClock(NOW);

    private DutyBotService service;

    @BeforeEach
    void setUp() {
        duty.snapshot = scheduleSnapshot(List.of("alice", "bob"), true);
        TransactionOperations tx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                txCount.incrementAndGet();
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        service = new DutyBotService(store, duty, incidents, notify, tx, clock, ZONE);
    }

    // ------------------------------------------------------------------ 会话

    @Test
    void createSessionDefaultsTitleAndBindsOwner() {
        var s = service.createSession(OWNER, null);
        assertThat(s.title()).isEqualTo("值班仿真会话");
        assertThat(s.owner()).isEqualTo(OWNER);
        assertThat(s.createdAt()).isEqualTo(NOW);
        assertThat(service.createSession(OWNER, "  夜间排查  ").title()).isEqualTo("夜间排查");
    }

    @Test
    void sessionsAreIsolatedPerOwner() {
        service.createSession(OWNER, "a");
        service.createSession("op-b", "b");
        assertThat(service.listSessions(OWNER, null, 50)).hasSize(1);
        assertThat(service.listSessions("op-b", null, 50)).hasSize(1);
        assertThat(service.listSessions("ghost", null, 50)).isEmpty();
    }

    // ------------------------------------------------------------------ 一问一答

    @Test
    void dutyQuestionAnswersFromRealSnapshotInOneTransaction() {
        UUID sid = service.createSession(OWNER, null).id();

        var result = service.postMessage(sid, OWNER, "今晚谁值班", "c-1").orElseThrow();

        assertThat(result.replayed()).isFalse();
        assertThat(result.userMessage().role()).isEqualTo("user");
        assertThat(result.botMessage().role()).isEqualTo("assistant");
        assertThat(result.botMessage().intent()).isEqualTo("DUTY_ONCALL");
        // 真实轮换解析：anchor+10 天 → position 0 = alice；通道链全真
        assertThat(result.botMessage().content())
                .contains("2026-09-11").contains("alice").contains("轮换")
                .contains("wecom-main").contains("WECOM");
        // 同事务：恰好一次 execute，两条消息同会话
        assertThat(txCount.get()).isEqualTo(1);
        assertThat(store.countMessages(sid)).isEqualTo(2);
        assertThat(store.findByClientMessageId(sid, "c-1")).isPresent();
    }

    @Test
    void incidentQueryListsRealRowsWithRefs() {
        incidents.rows.add(incidentRow("HighCpuUsage", "order-arena", "critical", "FIRING"));
        incidents.rows.add(incidentRow("DbLag", "order-arena", "warning", "FIRING"));
        UUID sid = service.createSession(OWNER, null).id();

        var result = service.postMessage(sid, OWNER, "服务 order-arena 的告警", null)
                .orElseThrow();

        assertThat(result.botMessage().intent()).isEqualTo("INCIDENT_QUERY");
        assertThat(result.botMessage().content())
                .contains("共 2 条").contains("HighCpuUsage").contains("order-arena");
        assertThat(result.botMessage().references())
                .containsExactlyInAnyOrder(
                        new DutyBotStore.Ref("incident", incidents.rows.get(0).incidentId().toString()),
                        new DutyBotStore.Ref("incident", incidents.rows.get(1).incidentId().toString()));
    }

    @Test
    void incidentDetailHonestBothWays() {
        var row = incidentRow("HighCpuUsage", "order-arena", "critical", "FIRING");
        incidents.rows.add(row);
        UUID sid = service.createSession(OWNER, null).id();

        var found = service.postMessage(sid, OWNER, "这个告警什么情况 " + row.incidentId(), null)
                .orElseThrow();
        assertThat(found.botMessage().intent()).isEqualTo("INCIDENT_DETAIL");
        assertThat(found.botMessage().content())
                .contains("HighCpuUsage").contains("尚未发起 RCA 调查")
                .contains(row.incidentId().toString());
        assertThat(found.botMessage().references())
                .containsExactly(new DutyBotStore.Ref("incident", row.incidentId().toString()));

        var missing = service.postMessage(sid, OWNER,
                "这个告警什么情况 " + UUID.randomUUID(), null).orElseThrow();
        assertThat(missing.botMessage().content()).contains("查询无结果").contains("不存在");
        assertThat(missing.botMessage().references()).isEmpty();
    }

    @Test
    void notifyStatusReportsRealCountsAndProblems() {
        notify.status = new NotifyStatusReader.OutboxStatus(41,
                Map.of("SENT", 38L, "PENDING", 2L, "DEAD", 1L),
                List.of(new NotifyStatusReader.ProblemRow(UUID.randomUUID(), UUID.randomUUID(),
                        "wecom", "DEAD", 5, "timeout", NOW)));
        UUID sid = service.createSession(OWNER, null).id();

        var result = service.postMessage(sid, OWNER, "通知发出去了吗", null).orElseThrow();

        assertThat(result.botMessage().intent()).isEqualTo("NOTIFY_STATUS");
        assertThat(result.botMessage().content())
                .contains("共 41 行").contains("DEAD=1").contains("SENT=38")
                .contains("channel=wecom").contains("已试 5 次");
        assertThat(result.botMessage().references()).hasSize(1);
    }

    @Test
    void emptyDataAnswersHonestlyWithoutFabrication() {
        duty.snapshot = emptySnapshot();
        incidents.rows.clear();
        notify.status = new NotifyStatusReader.OutboxStatus(0, Map.of(), List.of());
        UUID sid = service.createSession(OWNER, null).id();

        var dutyReply = service.postMessage(sid, OWNER, "今晚谁值班", null).orElseThrow();
        assertThat(dutyReply.botMessage().content()).contains("查询无结果");

        var incidentReply = service.postMessage(sid, OWNER, "现在有哪些告警", null).orElseThrow();
        assertThat(incidentReply.botMessage().content())
                .contains("查询无结果").contains("status=FIRING");

        var notifyReply = service.postMessage(sid, OWNER, "通知状态", null).orElseThrow();
        assertThat(notifyReply.botMessage().content()).contains("查询无结果");
    }

    @Test
    void unsupportedIntentAnswersCapabilityList() {
        UUID sid = service.createSession(OWNER, null).id();
        var result = service.postMessage(sid, OWNER, "帮我重启一下服务", null).orElseThrow();
        assertThat(result.botMessage().intent()).isEqualTo("UNSUPPORTED");
        assertThat(result.botMessage().content()).contains("暂不支持").contains("值班");
    }

    // ------------------------------------------------------------------ 越权与边界

    @Test
    void foreignSessionIsInvisible() {
        UUID sid = service.createSession(OWNER, null).id();
        assertThat(service.postMessage(sid, "op-b", "今晚谁值班", null)).isEmpty();
        assertThat(service.listMessages(sid, "op-b", null, 50)).isEmpty();
        assertThat(store.countMessages(sid)).isZero();
    }

    @Test
    void overlongContentRejectedBeforeAnyWrite() {
        UUID sid = service.createSession(OWNER, null).id();
        String longContent = "值".repeat(DutyBotService.MAX_CONTENT_CHARS + 1);
        assertThatThrownBy(() -> service.postMessage(sid, OWNER, longContent, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
        assertThat(store.countMessages(sid)).isZero();
        assertThat(txCount.get()).isZero();
    }

    @Test
    void blankContentRejected() {
        UUID sid = service.createSession(OWNER, null).id();
        assertThatThrownBy(() -> service.postMessage(sid, OWNER, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content 必填");
    }

    @Test
    void sessionMessageCapBlocksFurtherWrites() {
        UUID sid = service.createSession(OWNER, null).id();
        for (int i = 0; i < DutyBotService.MAX_MESSAGES_PER_SESSION; i++) {
            store.insertMessage(new DutyBotStore.NewMessage(UUID.randomUUID(), sid,
                    i % 2 == 0 ? "user" : "assistant",
                    "seed-" + i, i % 2 == 0 ? null : "HELP", List.of(), null, NOW));
        }
        assertThatThrownBy(() -> service.postMessage(sid, OWNER, "今晚谁值班", null))
                .isInstanceOf(DutyBotService.SessionFullException.class)
                .hasMessageContaining("上限");
        assertThat(store.countMessages(sid))
                .isEqualTo(DutyBotService.MAX_MESSAGES_PER_SESSION);
    }

    @Test
    void rateLimitBlocksBurstAndRecoversNextWindow() {
        UUID sid = service.createSession(OWNER, null).id();
        for (int i = 0; i < DutyBotService.RATE_LIMIT_PER_MINUTE; i++) {
            assertThat(service.postMessage(sid, OWNER, "第" + i + "条", null)).isPresent();
        }
        assertThatThrownBy(() -> service.postMessage(sid, OWNER, "超限", null))
                .isInstanceOf(DutyBotService.RateLimitExceededException.class);
        assertThat(store.countMessages(sid))
                .isEqualTo(DutyBotService.RATE_LIMIT_PER_MINUTE * 2L);
        // 窗口滚动后恢复
        clock.instant = NOW.plusSeconds(61);
        assertThat(service.postMessage(sid, OWNER, "恢复", null)).isPresent();
    }

    @Test
    void clientMessageIdReplayReturnsOriginalPairWithoutRewrite() {
        UUID sid = service.createSession(OWNER, null).id();
        var first = service.postMessage(sid, OWNER, "今晚谁值班", "cm-1").orElseThrow();
        int txAfterFirst = txCount.get();

        var replay = service.postMessage(sid, OWNER, "今晚谁值班", "cm-1").orElseThrow();

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(replay.botMessage().id()).isEqualTo(first.botMessage().id());
        assertThat(store.countMessages(sid)).isEqualTo(2);
        assertThat(txCount.get()).isEqualTo(txAfterFirst);
    }

    @Test
    void messagesPageBySeqCursorNewestFirst() {
        UUID sid = service.createSession(OWNER, null).id();
        for (int i = 0; i < 3; i++) {
            service.postMessage(sid, OWNER, "问题" + i, null);
        }
        var page1 = service.listMessages(sid, OWNER, null, 4).orElseThrow();
        assertThat(page1).hasSize(4);
        assertThat(page1.get(0).seq()).isGreaterThan(page1.get(3).seq());
        var page2 = service.listMessages(sid, OWNER, page1.get(3).seq(), 4).orElseThrow();
        assertThat(page2).hasSize(2);
        assertThat(page2.stream().map(DutyBotStore.MessageRow::seq))
                .allMatch(seq -> seq < page1.get(3).seq());
    }

    // ------------------------------------------------------------------ fakes

    private static IncidentQueryReader.IncidentRow incidentRow(String alertname, String service,
                                                               String severity, String status) {
        return new IncidentQueryReader.IncidentRow(UUID.randomUUID(),
                "key-" + alertname, alertname, service, severity, status,
                NOW.minusSeconds(3600), NOW, null, 3, 2, 1,
                null, null, null, "INFRA", "RULE", null);
    }

    private static DutyScheduleSnapshot scheduleSnapshot(List<String> members, boolean channel) {
        return new DutyScheduleSnapshot(UUID.randomUUID(), "主值班表", 3, NOW, NOW.plusSeconds(90),
                ZONE, RotationMath.Rotation.DAILY, LocalDate.of(2026, 9, 1),
                LocalTime.of(9, 0),
                List.of(new DutyScheduleSnapshot.Layer(0, members)),
                List.of(),
                channel ? List.of(new DutyScheduleSnapshot.Channel(UUID.randomUUID(),
                        "wecom-main", "WECOM", "K_WECOM", null, 1, false, true))
                        : List.of());
    }

    private static DutyScheduleSnapshot emptySnapshot() {
        return new DutyScheduleSnapshot(UUID.randomUUID(), "(none)", 0, NOW,
                NOW.plusSeconds(90), ZONE, RotationMath.Rotation.DAILY,
                LocalDate.EPOCH, LocalTime.MIDNIGHT, List.of(), List.of(), List.of());
    }

    /** PG 语义镜像：seq 单调、会话/消息分桶、游标过滤、幂等锚查询 */
    private static final class InMemoryDutyBotStore implements DutyBotStore {
        private final Map<UUID, SessionRow> sessions = new LinkedHashMap<>();
        private final Map<UUID, List<MessageRow>> messages = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public UUID insertSession(SessionRow session) {
            sessions.put(session.id(), session);
            messages.put(session.id(), new ArrayList<>());
            return session.id();
        }

        @Override
        public Optional<SessionRow> findSession(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public List<SessionRow> listSessions(String owner, SessionCursor cursor, int limit) {
            return sessions.values().stream()
                    .filter(s -> s.owner().equals(owner))
                    .filter(s -> cursor == null
                            || s.createdAt().isBefore(cursor.at())
                            || (s.createdAt().equals(cursor.at())
                                    && s.id().compareTo(cursor.id()) < 0))
                    .sorted(Comparator.comparing(SessionRow::createdAt).reversed()
                            .thenComparing(Comparator.comparing(SessionRow::id).reversed()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countMessages(UUID sessionId) {
            return messages.getOrDefault(sessionId, List.of()).size();
        }

        @Override
        public Optional<MessageRow> findByClientMessageId(UUID sessionId, String clientMessageId) {
            return messages.getOrDefault(sessionId, List.of()).stream()
                    .filter(m -> clientMessageId.equals(m.clientMessageId()))
                    .findFirst();
        }

        @Override
        public Optional<MessageRow> findReplyAfter(UUID sessionId, long afterSeq) {
            return messages.getOrDefault(sessionId, List.of()).stream()
                    .filter(m -> "assistant".equals(m.role()) && m.seq() > afterSeq)
                    .min(Comparator.comparingLong(MessageRow::seq));
        }

        @Override
        public MessageRow insertMessage(NewMessage message) {
            MessageRow row = new MessageRow(seq.getAndIncrement(), message.id(),
                    message.sessionId(), message.role(), message.content(), message.intent(),
                    message.references() == null ? List.of() : message.references(),
                    message.clientMessageId(), message.createdAt());
            messages.computeIfAbsent(message.sessionId(), k -> new ArrayList<>()).add(row);
            return row;
        }

        @Override
        public List<MessageRow> listMessages(UUID sessionId, Long cursorSeq, int limit) {
            return messages.getOrDefault(sessionId, List.of()).stream()
                    .filter(m -> cursorSeq == null || m.seq() < cursorSeq)
                    .sorted(Comparator.comparingLong(MessageRow::seq).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    /** 值班面假件：loadSnapshot 返回夹具，其余写面方法不支持（服务只读值班表） */
    private static final class FakeDutyStore implements DutyStore {
        private DutyScheduleSnapshot snapshot;

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
        public boolean insertDeliveryIfAbsent(UUID id, DutyScheduleSnapshot.Channel c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markRead(UUID id, Instant readAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DutyNotificationView> listNotifications(String cursor, int limit,
                                                            boolean unreadOnly) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long unreadCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<NotificationFeedView> listFeed(String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> ids) {
            throw new UnsupportedOperationException();
        }
    }

    /** incident 投影假件：list/detail 从行表过滤，其余读面不支持 */
    private static final class FakeIncidentReader implements IncidentQueryReader {
        private final List<IncidentRow> rows = new ArrayList<>();

        @Override
        public IncidentPage listIncidents(String status, String severity, String service,
                                          String q, String category, Instant from, Instant to,
                                          Boolean hasOwner, KeysetCursor cursor, int limit) {
            List<IncidentRow> filtered = rows.stream()
                    .filter(r -> status == null || r.status().equals(status))
                    .filter(r -> service == null || service.equals(r.service()))
                    .limit(limit)
                    .toList();
            long total = rows.stream()
                    .filter(r -> status == null || r.status().equals(status))
                    .filter(r -> service == null || service.equals(r.service()))
                    .count();
            return new IncidentPage(filtered, total, false);
        }

        @Override
        public Optional<IncidentDetail> detail(UUID incidentId) {
            return rows.stream().filter(r -> r.incidentId().equals(incidentId)).findFirst()
                    .map(r -> new IncidentDetail(r, Map.of(), Map.of(), List.of(), null,
                            new CategoryDetail("INFRA-ALERTNAME", "ux01-rules-v1", NOW,
                                    null, null, null, null)));
        }

        @Override
        public Facets facets(String status, String service, String q) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IncidentSummary summary(Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AlertOverview overview(Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeNotifyReader implements NotifyStatusReader {
        private OutboxStatus status = new OutboxStatus(0, Map.of(), List.of());

        @Override
        public OutboxStatus summarize(int problemLimit) {
            return status;
        }
    }

    /** 可变时钟（B-41 律：Supplier 注入，测试可推进） */
    private static final class MutableClock implements Supplier<Instant> {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant get() {
            return instant;
        }
    }
}
