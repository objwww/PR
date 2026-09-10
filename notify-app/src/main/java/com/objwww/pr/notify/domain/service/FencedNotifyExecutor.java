package com.objwww.pr.notify.domain.service;

import com.objwww.pr.notify.domain.channel.NotificationChannel;
import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.port.StaleClaimException;

import java.time.Instant;
import java.util.Objects;

/**
 * 栅栏包裹的通知发送执行器（M3-20/23；§6.8"外部 HTTP 调用不持 DB 事务"）：
 * 渲染（纯函数）→ 渠道发送（事务外）→ 按 {@code SendResult} 分类短事务落账。
 *
 * <p>冻结语义（M3-09/M3-23）：2xx+errcode=0→SENT（F20：200+errcode≠0 是平台明确
 * 拒绝→Retryable，不标 SENT）；429→RETRY_WAIT 持久化退避<b>不耗预算</b>
 * （渠道限流不是投递失败，落码自决）；5xx/连接失败/业务码拒绝→RETRY_WAIT 耗预算、
 * 耗尽→DEAD；4xx→DEAD 终态；请求已发出结果未知→DEAD（UNKNOWN 不自动重发，诚实话术
 * 在 last_error；重复策略=人工复核可重投，operation_id 可检测）；渲染失败/渠道未配置
 * →DEAD（确定性拒绝）。epoch 栅栏未中→DEFERRED，绝不覆盖新租约。
 * 渠道行每笔终态后同步 publication 聚合（任一 SENT→SENT；全终态无一 SENT→DEAD）。
 *
 * <p>EX-C2a 最长通知期限：attempt 预算之外的第二道墙钟闸——行龄超过
 * {@code maxNotificationAge} 的行不再重试（429 与 5xx 同受封顶），DEAD 落
 * notification_deadline_exceeded；「不无限退避」的期限面，原点=notify_outbox.created_at。
 * 回执语义：机器人 HTTP 接收（errcode=0）只证明机器人收到，不证明值班员阅读。
 */
public final class FencedNotifyExecutor implements NotifySender {

    public enum Outcome {SENT, RETRY_WAIT, DEAD, SUPPRESSED, DEFERRED}

    private final NotifyOutboxStore store;
    private final NotificationRenderer renderer;
    private final ChannelRouter router;
    private final Backoff backoff;
    // B-41：时钟必须每次取值（Supplier）——传 Instant 值会把 bean 创建时刻冻结成
    // 永恒"现在"：sent_at 撒谎（=启动时刻）、退避 available_at 恒在过去（重试风暴、
    // 退避失效）、期限墙钟闸失效。真机证据：drill3 leg A sent_at=容器启动时刻。
    private final java.util.function.Supplier<Instant> clock;
    private final java.time.Duration maxNotificationAge;

    /** 退避策略（指数 + 上限；429 优先渠道 Retry-After） */
    public interface Backoff {
        Instant nextAttemptAt(int failedAttempts, Instant from, long retryAfterSeconds);
    }

    /** 渠道路由：outbox.channel → 三档渠道实例；未配置 = 确定性 DEAD 而非静默丢弃 */
    public interface ChannelRouter {
        NotificationChannel resolve(String channel);
    }

    public FencedNotifyExecutor(NotifyOutboxStore store, NotificationRenderer renderer,
                                ChannelRouter router, Backoff backoff,
                                java.util.function.Supplier<Instant> clock,
                                java.time.Duration maxNotificationAge) {
        this.store = Objects.requireNonNull(store);
        this.renderer = Objects.requireNonNull(renderer);
        this.router = Objects.requireNonNull(router);
        this.backoff = Objects.requireNonNull(backoff);
        this.clock = Objects.requireNonNull(clock);
        this.maxNotificationAge = Objects.requireNonNull(maxNotificationAge);
    }

    public Outcome execute(ClaimedNotification notification) {
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
        }
        if (result instanceof NotificationChannel.SendResult.Permanent permanent) {
            return dead(notification, "permanent_reject", permanent.error());
        }
        if (result instanceof NotificationChannel.SendResult.OutcomeUnknown unknown) {
            // 结果未知不自动重发（M3-23 冻结）：终态留档，人工复核决定是否补发
            return dead(notification, "outcome_unknown", unknown.error());
        }
        if (result instanceof NotificationChannel.SendResult.Suppressed suppressed) {
            try {
                store.markSuppressed(notification.id(), notification.leaseEpoch(),
                        errorJson("validate_only", suppressed.note()));
                store.syncPublication(notification.publicationId());
                return Outcome.SUPPRESSED;
            } catch (StaleClaimException e) {
                return Outcome.DEFERRED;
            }
        }
        throw new IllegalStateException("未知发送结局: " + result.getClass());
    }

    private Outcome retry(ClaimedNotification notification, boolean consumeAttempt,
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
    }

    private Outcome dead(ClaimedNotification notification, String reason, String detail) {
        try {
            store.markDead(notification.id(), notification.leaseEpoch(),
                    errorJson(reason, detail));
            store.syncPublication(notification.publicationId());
            return Outcome.DEAD;
        } catch (StaleClaimException e) {
            return Outcome.DEFERRED;
        }
    }

    private static String errorJson(String reason, String detail) {
        String safe = detail == null ? "" : detail.replace("\\", "\\\\").replace("\"", "'");
        return "{\"reason\":\"" + reason + "\",\"error\":\"" + safe + "\"}";
    }
}
