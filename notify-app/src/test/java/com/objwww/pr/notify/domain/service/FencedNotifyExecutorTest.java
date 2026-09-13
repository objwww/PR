package com.objwww.pr.notify.domain.service;

import com.objwww.pr.notify.domain.channel.NotificationChannel;
import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.port.StaleClaimException;
import com.objwww.pr.notify.domain.service.FencedNotifyExecutor.Outcome;
import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-23 执行器冻结语义：2xx→SENT；429 退避不耗预算；5xx 耗预算、耗尽→DEAD；
 * 4xx→DEAD；结果未知→DEAD 不自动重发；VALIDATE_ONLY→SUPPRESSED 不冒充 SENT；
 * 渲染失败/渠道未配置→DEAD 零触网；epoch 栅栏未中→DEFERRED 且不落账。
 */
class FencedNotifyExecutorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private FakeStore store;
    private StubRouter router;
    private FencedNotifyExecutor executor;
    private Instant currentTime = NOW;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        router = new StubRouter();
        executor = new FencedNotifyExecutor(store, new NotificationRenderer(120, 600),
                router, (failedAttempts, from, retryAfter) -> retryAfter > 0
                ? from.plusSeconds(retryAfter)
                : from.plus(Duration.ofMinutes((long) Math.pow(2, failedAttempts - 1))),
                () -> currentTime, MAX_AGE);
    }

    private ClaimedNotification notification() {
        return notificationCreatedAt(NOW.minusSeconds(1));
    }

    private ClaimedNotification notificationCreatedAt(Instant createdAt) {
        return new ClaimedNotification(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "dingtalk-test", "am3-notice-v1", UUID.randomUUID(),
                "{\"operation_id\":\"" + UUID.randomUUID()
                        + "\",\"notice\":\"候选\",\"summary\":\"s\",\"candidate\":true}",
                0, 5, 7, createdAt);
    }

    @Test
    @DisplayName("2xx → SENT + publication 同步")
    void deliveredMarksSent() {
        ClaimedNotification n = notification();
        router.channel = (rendered, operationId) -> new NotificationChannel.SendResult.Delivered();

        assertThat(executor.execute(n)).isEqualTo(Outcome.SENT);
        assertThat(store.sent.get(n.id())).isEqualTo(NOW);
        assertThat(store.synced).containsExactly(n.publicationId());
    }

    @Test
    @DisplayName("B-41：时钟逐次取值——sent_at 跟随真实执行时刻，期限闸随墙钟推进")
    void clockIsReadPerExecutionNotFrozenAtConstruction() {
        router.channel = (rendered, operationId) -> new NotificationChannel.SendResult.Delivered();

        ClaimedNotification first = notification();
        assertThat(executor.execute(first)).isEqualTo(Outcome.SENT);
        assertThat(store.sent.get(first.id())).isEqualTo(NOW);

        Instant later = NOW.plus(Duration.ofHours(2));
        currentTime = later;
        ClaimedNotification second = notification();
        assertThat(executor.execute(second)).isEqualTo(Outcome.SENT);
        assertThat(store.sent.get(second.id()))
                .as("sent_at 必须是发送时刻（B-41：bean 创建时刻冻结 = 撒谎）")
                .isEqualTo(later);

        // 期限闸：RETRY_WAIT 行老化跨过 max-notification-age 后，重试必须被墙钟闸击落
        router.channel = (rendered, operationId) -> new NotificationChannel.SendResult.RateLimited(30L);
        ClaimedNotification aging = notificationCreatedAt(NOW.minusSeconds(1));
        assertThat(executor.execute(aging)).isEqualTo(Outcome.RETRY_WAIT);
        currentTime = NOW.minusSeconds(1).plus(MAX_AGE).plusSeconds(1);
        assertThat(executor.execute(aging)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(aging.id())).contains("notification_deadline_exceeded");
    }

    @Test
    @DisplayName("EX-C2a 最长通知期限：行龄超 max-notification-age → DEAD（不无限退避），attempt 未耗尽同样终态")
    void retryBeyondDeadlineIsDead() {
        ClaimedNotification aged = notificationCreatedAt(NOW.minus(MAX_AGE).minusSeconds(1));
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Retryable("http_503");

        assertThat(executor.execute(aged)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(aged.id())).contains("notification_deadline_exceeded");

        // 429 面（不耗预算）同样受墙钟期限封顶
        ClaimedNotification agedLimited = notificationCreatedAt(NOW.minus(MAX_AGE).minusSeconds(1));
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.RateLimited(30L);
        assertThat(executor.execute(agedLimited)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(agedLimited.id()))
                .contains("notification_deadline_exceeded");

        // 期限内的行照常重试（对照面）
        assertThat(executor.execute(notification())).isEqualTo(Outcome.RETRY_WAIT);
    }

    @Test
    @DisplayName("429：RETRY_WAIT 用 Retry-After 且 attempt_count 不增（限流不耗预算）")
    void rateLimitedDoesNotConsumeBudget() {
        ClaimedNotification n = notification();
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.RateLimited(120L);

        assertThat(executor.execute(n)).isEqualTo(Outcome.RETRY_WAIT);
        assertThat(store.retryAvailableAt.get(n.id())).isEqualTo(NOW.plusSeconds(120));
        assertThat(store.retryBumps.get(n.id())).isZero();
    }

    @Test
    @DisplayName("5xx 指数退避耗预算；达到 max_attempts → DEAD（retry_budget_exhausted）")
    void serverErrorsConsumeBudgetUntilExhausted() {
        ClaimedNotification first = notification();
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Retryable("http_503");
        assertThat(executor.execute(first)).isEqualTo(Outcome.RETRY_WAIT);
        assertThat(store.retryBumps.get(first.id())).isEqualTo(1);
        assertThat(store.retryAvailableAt.get(first.id())).isEqualTo(NOW.plusSeconds(60));

        ClaimedNotification exhausted = new ClaimedNotification(UUID.randomUUID(),
                first.publicationId(), first.reportId(), first.channel(),
                first.templateVersion(), first.operationId(), first.payloadJson(),
                4, 5, 7, NOW.minusSeconds(1));
        assertThat(executor.execute(exhausted)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(exhausted.id())).contains("retry_budget_exhausted");
    }

    @Test
    @DisplayName("4xx → DEAD 终态；结果未知（请求已发出）→ DEAD 且不自动重发")
    void permanentAndUnknownAreTerminal() {
        ClaimedNotification rejected = notification();
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Permanent("http_401");
        assertThat(executor.execute(rejected)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(rejected.id())).contains("permanent_reject");

        ClaimedNotification unknown = notification();
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.OutcomeUnknown("response timeout");
        assertThat(executor.execute(unknown)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(unknown.id())).contains("outcome_unknown");
    }

    @Test
    @DisplayName("VALIDATE_ONLY：SUPPRESSED 落账（不冒充 SENT）；渲染失败 → DEAD 零触网")
    void validateOnlyAndRenderFailure() {
        ClaimedNotification validated = notification();
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Suppressed("validate_only_passed");
        assertThat(executor.execute(validated)).isEqualTo(Outcome.SUPPRESSED);
        assertThat(store.suppressedNote.get(validated.id())).contains("validate_only");
        assertThat(store.sent).doesNotContainKey(validated.id());

        ClaimedNotification badPayload = new ClaimedNotification(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "dingtalk-test", "v1",
                UUID.randomUUID(), "not-json", 0, 5, 7, NOW.minusSeconds(1));
        router.channel = (rendered, operationId) -> {
            throw new AssertionError("渲染失败的行绝不允许触网");
        };
        assertThat(executor.execute(badPayload)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(badPayload.id())).contains("render_rejected");

        ClaimedNotification unknownChannel = new ClaimedNotification(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "ghost-channel", "v1",
                UUID.randomUUID(), badPayload.payloadJson().replace("not-json", "{\"operation_id\":\"" + UUID.randomUUID() + "\"}"),
                0, 5, 7, NOW.minusSeconds(1));
        assertThat(executor.execute(unknownChannel)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(unknownChannel.id())).contains("channel_not_configured");
    }

    @Test
    @DisplayName("epoch 栅栏未中（僵尸 worker）：DEFERRED 且绝不覆盖新租约")
    void defersOnStaleClaim() {
        ClaimedNotification stale = notification();
        router.channel = (rendered, operationId) -> new NotificationChannel.SendResult.Delivered();
        store.staleIds.add(stale.id());

        assertThat(executor.execute(stale)).isEqualTo(Outcome.DEFERRED);
        assertThat(store.sent).doesNotContainKey(stale.id());
    }

    // ------------------------------------------------------------------ 假件

    // ---------------------------------------------- RV05/06/07（审查方案 A 批）

    @Test
    @DisplayName("RV06：router 返回 null（未知/已删渠道）→ 契约面 channel_not_configured→DEAD，零触网不 NPE")
    void nullRouterResultIsContractualDeadNotNpe() {
        ClaimedNotification n = notification();
        router.channel = null; // resolve 返回 null 而非抛异常

        assertThat(executor.execute(n)).isEqualTo(Outcome.DEAD);
        assertThat(store.deadReason.get(n.id())).contains("channel_not_configured");
    }

    @Test
    @DisplayName("RV05：平台错误文本含引号/换行/TAB/反斜杠 → last_error 仍可解析 JSON（固定 reason 码+detail 保真）")
    void platformErrorTextStaysValidJson() throws Exception {
        ClaimedNotification n = notification();
        String nasty = "business errcode=123 \"quoted\" line1\nline2\ttab \\ slash";
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Retryable(nasty);

        assertThat(executor.execute(n)).isEqualTo(Outcome.RETRY_WAIT);

        String lastError = store.retryError.get(n.id());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastError);
        assertThat(node.get("reason").asText()).as("固定原因码，不夹带自由文本")
                .isEqualTo("channel_retryable");
        assertThat(node.get("error").asText()).as("detail 逐字保真（Jackson 转义）")
                .isEqualTo(nasty);
    }

    @Test
    @DisplayName("RV07：期限闸前置——超龄首发零触网 DEAD（渠道零调用）；等于边界可发")
    void ageGateGuardsFirstSendToo() {
        java.util.concurrent.atomic.AtomicInteger sendCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        ClaimedNotification aged = notificationCreatedAt(NOW.minus(MAX_AGE).minusSeconds(1));
        router.channel = (rendered, operationId) -> {
            sendCalls.incrementAndGet();
            return new NotificationChannel.SendResult.Delivered();
        };

        assertThat(executor.execute(aged)).isEqualTo(Outcome.DEAD);
        assertThat(sendCalls.get()).as("超龄行零触网（连渠道面都不进）").isZero();
        assertThat(store.deadReason.get(aged.id()))
                .contains("notification_deadline_exceeded");

        // 等于边界（createdAt+maxAge == now）：isAfter=false → 可正常首发
        ClaimedNotification edge = notificationCreatedAt(NOW.minus(MAX_AGE));
        router.channel = (rendered, operationId) ->
                new NotificationChannel.SendResult.Delivered();
        assertThat(executor.execute(edge)).isEqualTo(Outcome.SENT);
    }

    @Test
    @DisplayName("RV07：sent_at=发送确认后时刻（发送中推进时钟，落账跟进而非 startedAt）")
    void sentAtIsConfirmationTimeNotStartedAt() {
        router.channel = (rendered, operationId) -> {
            currentTime = NOW.plusSeconds(30); // 发送执行中时钟推进
            return new NotificationChannel.SendResult.Delivered();
        };
        ClaimedNotification n = notification();

        assertThat(executor.execute(n)).isEqualTo(Outcome.SENT);
        assertThat(store.sent.get(n.id())).as("sent_at=确认后时钟").isEqualTo(NOW.plusSeconds(30));
    }

    private static final class FakeStore implements NotifyOutboxStore {
        final Map<UUID, Instant> sent = new HashMap<>();
        final Map<UUID, Instant> retryAvailableAt = new HashMap<>();
        final Map<UUID, Integer> retryBumps = new HashMap<>();
        final Map<UUID, String> deadReason = new HashMap<>();
        final Map<UUID, String> retryError = new HashMap<>();
        final Map<UUID, String> suppressedNote = new HashMap<>();
        final List<UUID> synced = new ArrayList<>();
        final List<UUID> staleIds = new ArrayList<>();

        private void assertFresh(UUID id) {
            if (staleIds.contains(id)) {
                throw new StaleClaimException(id);
            }
        }

        @Override
        public List<ClaimedNotification> claim(String leaseOwner, Duration leaseDuration,
                                               int batchSize) {
            return List.of();
        }

        @Override
        public void markSent(UUID id, long leaseEpoch, Instant sentAt) {
            assertFresh(id);
            sent.put(id, sentAt);
        }

        @Override
        public void markRetryWait(UUID id, long leaseEpoch, Instant availableAt,
                                  boolean consumeAttempt, String lastErrorJson) {
            assertFresh(id);
            retryAvailableAt.put(id, availableAt);
            retryBumps.put(id, consumeAttempt ? 1 : 0);
            retryError.put(id, lastErrorJson);
        }

        @Override
        public void markDead(UUID id, long leaseEpoch, String lastErrorJson) {
            assertFresh(id);
            deadReason.put(id, lastErrorJson);
        }

        @Override
        public void markSuppressed(UUID id, long leaseEpoch, String noteJson) {
            assertFresh(id);
            suppressedNote.put(id, noteJson);
        }

        @Override
        public void syncPublication(UUID publicationId) {
            synced.add(publicationId);
        }
    }

    private static final class StubRouter implements FencedNotifyExecutor.ChannelRouter {
        private NotificationChannel channel;

        @Override
        public NotificationChannel resolve(String channelName) {
            if ("ghost-channel".equals(channelName)) {
                throw new IllegalStateException("未配置渠道: " + channelName);
            }
            return channel;
        }
    }
}
