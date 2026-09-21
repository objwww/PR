# -*- coding: utf-8 -*-
import io

p = r'notify-app/src/test/java/com/objwww/pr/notify/domain/service/FencedNotifyExecutorTest.java'
t = io.open(p, encoding='utf-8').read()

old = """        final Map<UUID, String> deadReason = new HashMap<>();"""
new = """        final Map<UUID, String> deadReason = new HashMap<>();
        final Map<UUID, String> retryError = new HashMap<>();"""
assert old in t
t = t.replace(old, new)

old = """        public void markRetryWait(UUID id, long leaseEpoch, Instant availableAt,
                                  boolean consumeAttempt, String lastErrorJson) {
            assertFresh(id);
            retryAvailableAt.put(id, availableAt);
            retryBumps.put(id, consumeAttempt ? 1 : 0);
        }"""
new = """        public void markRetryWait(UUID id, long leaseEpoch, Instant availableAt,
                                  boolean consumeAttempt, String lastErrorJson) {
            assertFresh(id);
            retryAvailableAt.put(id, availableAt);
            retryBumps.put(id, consumeAttempt ? 1 : 0);
            retryError.put(id, lastErrorJson);
        }"""
assert old in t
t = t.replace(old, new)

# --- 四个新测试插在 FakeStore 夹具前
old = """    class FakeStore implements NotifyOutboxStore {"""
# careful: it's "private static final class FakeStore"? earlier read: "class FakeStore implements NotifyOutboxStore {" preceded by modifier on same line?
old_sig = "    private static final class FakeStore implements NotifyOutboxStore {"
if old_sig not in t:
    old_sig = "    class FakeStore implements NotifyOutboxStore {"
new_block = """    // ---------------------------------------------- RV05/06/07（审查方案 A 批）

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
        String nasty = "business errcode=123 \\"quoted\\" line1\\nline2\\ttab \\\\ slash";
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

""" + old_sig
t = t.replace(old_sig, new_block)

io.open(p, 'w', encoding='utf-8').write(t)
print('tests added')
