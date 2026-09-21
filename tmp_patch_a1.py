# -*- coding: utf-8 -*-
import io

p = r'notify-app/src/main/java/com/objwww/pr/notify/domain/service/FencedNotifyExecutor.java'
t = io.open(p, encoding='utf-8').read()

# --- imports + Jackson field
old = """import java.time.Instant;
import java.util.Objects;"""
new = """import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;"""
assert old in t
t = t.replace(old, new)

# --- javadoc: 期限前置 + sentAt 语义 + RV05/RV06 面
old = """ * <p>EX-C2a 最长通知期限：attempt 预算之外的第二道墙钟闸——行龄超过
 * {@code maxNotificationAge} 的行不再重试（429 与 5xx 同受封顶），DEAD 落
 * notification_deadline_exceeded；「不无限退避」的期限面，原点=notify_outbox.created_at。
 * 回执语义：机器人 HTTP 接收（errcode=0）只证明机器人收到，不证明值班员阅读。
 */"""
new = """ * <p>EX-C2a 最长通知期限（RV07 收紧）：attempt 预算之外的第二道墙钟闸——
 * <b>execute 入口即判</b>（超龄行零触网零渲染，首发与重试同受封顶），retry 分支保留
 * 二次检查（防一次长耗时发送跨越边界后再安排旧消息）；DEAD 落
 * notification_deadline_exceeded，原点=notify_outbox.created_at。sent_at=发送
 * <b>确认后</b>时钟取值（发送前取值只是 startedAt，不能准确充当 sentAt）。
 * <p>RV05/RV06：last_error 一律 Jackson 序列化（平台 errmsg 含引号/换行不产生
 * 非法 JSON——非法落账会卡死 CLAIMED 并破坏重试预算）；reason=固定原因码、
 * detail 限长；router 返回 null（未知/已删渠道）是契约面 → channel_not_configured
 * →DEAD，配置删除后的积压行稳定终态，不再靠租约重领空转。
 * 回执语义：机器人 HTTP 接收（errcode=0）只证明机器人收到，不证明值班员阅读。
 */"""
assert old in t
t = t.replace(old, new)

# --- ObjectMapper 常量
old = """public final class FencedNotifyExecutor implements NotifySender {

    public enum Outcome {SENT, RETRY_WAIT, DEAD, SUPPRESSED, DEFERRED}"""
new = """public final class FencedNotifyExecutor implements NotifySender {

    /** RV05：last_error 序列化唯一出口（手拼 JSON 在含换行/引号的合法错误文本上
     * 产生非法 jsonb，卡死 CLAIMED——禁回退） */
    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
    /** detail 限长（诊断文本非自由存储；现有脱敏/限长纪律同源） */
    static final int MAX_DETAIL_CHARS = 1000;

    public enum Outcome {SENT, RETRY_WAIT, DEAD, SUPPRESSED, DEFERRED}"""
assert old in t
t = t.replace(old, new)

# --- execute：期限前置 + null 渠道 + sentAt 确认后取值
old = """    public Outcome execute(ClaimedNotification notification) {
        Instant now = clock.get();
        NotificationRenderer.RenderedNotification rendered;
        try {
            rendered = renderer.render(notification.payloadJson());
        } catch (RuntimeException e) {
            return dead(notification, "render_rejected", e.getMessage());
        }

        NotificationChannel channel;
        try {
            channel = router.resolve(notification.channel());
        } catch (RuntimeException e) {
            return dead(notification, "channel_not_configured", e.getMessage());
        }

        NotificationChannel.SendResult result = channel.send(rendered,
                notification.operationId());
        if (result instanceof NotificationChannel.SendResult.Delivered) {
            try {
                store.markSent(notification.id(), notification.leaseEpoch(), now);
                store.syncPublication(notification.publicationId());
                return Outcome.SENT;
            } catch (StaleClaimException e) {
                return Outcome.DEFERRED;
            }
        }
        if (result instanceof NotificationChannel.SendResult.RateLimited limited) {
            return retry(notification, false, limited.retryAfterSeconds(), "rate_limited");
        }
        if (result instanceof NotificationChannel.SendResult.Retryable retryable) {
            int nextAttempt = notification.attemptCount() + 1;
            if (nextAttempt >= notification.maxAttempts()) {
                return dead(notification, "retry_budget_exhausted", retryable.error()
                        + " (attempt " + nextAttempt + "/" + notification.maxAttempts() + ")");
            }
            return retry(notification, true, 0, retryable.error());
        }"""
new = """    public Outcome execute(ClaimedNotification notification) {
        // EX-C2a/RV07：期限闸前置到入口——超龄行零触网零渲染（首发同受墙钟封顶，
        // 不再只挡重试分支）；等于边界可发、严格超过即终态
        if (clock.get().isAfter(notification.createdAt().plus(maxNotificationAge))) {
            return dead(notification, "notification_deadline_exceeded",
                    "age beyond max-notification-age");
        }
        NotificationRenderer.RenderedNotification rendered;
        try {
            rendered = renderer.render(notification.payloadJson());
        } catch (RuntimeException e) {
            return dead(notification, "render_rejected", e.getMessage());
        }

        NotificationChannel channel;
        try {
            channel = router.resolve(notification.channel());
        } catch (RuntimeException e) {
            return dead(notification, "channel_not_configured", e.getMessage());
        }
        if (channel == null) {
            // RV06：未知/已删渠道的 resolve 返回 null 不是异常——契约面稳定 DEAD，
            // 积压行不再靠租约重领空转
            return dead(notification, "channel_not_configured",
                    "channel not configured: " + notification.channel());
        }

        NotificationChannel.SendResult result = channel.send(rendered,
                notification.operationId());
        if (result instanceof NotificationChannel.SendResult.Delivered) {
            try {
                // RV07：sent_at=发送确认后取值（发送前取值只是 startedAt）
                store.markSent(notification.id(), notification.leaseEpoch(), clock.get());
                store.syncPublication(notification.publicationId());
                return Outcome.SENT;
            } catch (StaleClaimException e) {
                return Outcome.DEFERRED;
            }
        }
        if (result instanceof NotificationChannel.SendResult.RateLimited limited) {
            return retry(notification, false, limited.retryAfterSeconds(),
                    "rate_limited", null);
        }
        if (result instanceof NotificationChannel.SendResult.Retryable retryable) {
            int nextAttempt = notification.attemptCount() + 1;
            if (nextAttempt >= notification.maxAttempts()) {
                return dead(notification, "retry_budget_exhausted", retryable.error()
                        + " (attempt " + nextAttempt + "/" + notification.maxAttempts() + ")");
            }
            // RV05：reason=固定原因码，detail=上游诊断文本（分立，不互串）
            return retry(notification, true, 0, "channel_retryable", retryable.error());
        }"""
assert old in t
t = t.replace(old, new)

# --- retry：reasonCode/detail 分立 + 二次期限检查保留
old = """    private Outcome retry(ClaimedNotification notification, boolean consumeAttempt,
                          long retryAfterSeconds, String reason) {
        Instant now = clock.get();
        // EX-C2a 墙钟闸：期限外不再重试（attempt 预算是次数闸，这是时间闸——双闸封顶）
        if (now.isAfter(notification.createdAt().plus(maxNotificationAge))) {
            return dead(notification, "notification_deadline_exceeded",
                    reason + " (age beyond max-notification-age)");
        }
        try {
            store.markRetryWait(notification.id(), notification.leaseEpoch(),
                    backoff.nextAttemptAt(notification.attemptCount() + 1, now,
                            retryAfterSeconds),
                    consumeAttempt, errorJson(reason, reason));
            store.syncPublication(notification.publicationId());
            return Outcome.RETRY_WAIT;
        } catch (StaleClaimException e) {
            return Outcome.DEFERRED;
        }
    }"""
new = """    private Outcome retry(ClaimedNotification notification, boolean consumeAttempt,
                          long retryAfterSeconds, String reasonCode, String detail) {
        Instant now = clock.get();
        // EX-C2a 二次墙钟闸：一次长耗时发送可能跨越边界——不安排旧消息再试
        if (now.isAfter(notification.createdAt().plus(maxNotificationAge))) {
            return dead(notification, "notification_deadline_exceeded",
                    reasonCode + " (age beyond max-notification-age)");
        }
        try {
            store.markRetryWait(notification.id(), notification.leaseEpoch(),
                    backoff.nextAttemptAt(notification.attemptCount() + 1, now,
                            retryAfterSeconds),
                    consumeAttempt, errorJson(reasonCode, detail));
            store.syncPublication(notification.publicationId());
            return Outcome.RETRY_WAIT;
        } catch (StaleClaimException e) {
            return Outcome.DEFERRED;
        }
    }"""
assert old in t
t = t.replace(old, new)

# --- errorJson → Jackson
old = """    private static String errorJson(String reason, String detail) {
        String safe = detail == null ? "" : detail.replace("\\\\", "\\\\\\\\").replace("\\"", "'");
        return "{\\"reason\\":\\"" + reason + "\\",\\"error\\":\\"" + safe + "\\"}";
    }"""
new = """    /** RV05：Jackson 序列化（引号/换行/控制字符天然合法）；reason=固定原因码，
     * detail 限长——非法 last_error 会卡死 CLAIMED 并破坏重试预算 */
    private static String errorJson(String reason, String detail) {
        var node = ERROR_JSON.createObjectNode();
        node.put("reason", reason);
        String safe = detail == null ? "" : detail;
        if (safe.length() > MAX_DETAIL_CHARS) {
            safe = safe.substring(0, MAX_DETAIL_CHARS) + "…(truncated)";
        }
        node.put("error", safe);
        return node.toString();
    }"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('executor ok')
