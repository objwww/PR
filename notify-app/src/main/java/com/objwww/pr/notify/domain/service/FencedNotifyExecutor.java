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
 * <p>冻结语义（M3-09/M3-23）：2xx→SENT；429→RETRY_WAIT 持久化退避<b>不耗预算</b>
 * （渠道限流不是投递失败，落码自决）；5xx/连接失败→RETRY_WAIT 耗预算、耗尽→DEAD；
 * 4xx→DEAD 终态；请求已发出结果未知→DEAD（UNKNOWN 不自动重发，诚实话术在 last_error）；
 * 渲染失败/渠道未配置→DEAD（确定性拒绝）。epoch 栅栏未中→DEFERRED，绝不覆盖新租约。
 * 渠道行每笔终态后同步 publication 聚合（任一 SENT→SENT；全终态无一 SENT→DEAD）。
 */
public final class FencedNotifyExecutor implements NotifySender {

    public enum Outcome {SENT, RETRY_WAIT, DEAD, SUPPRESSED, DEFERRED}

    private final NotifyOutboxStore store;
    private final NotificationRenderer renderer;
    private final ChannelRouter router;
    private final Backoff backoff;
    private final Instant now;

    /** 退避策略（指数 + 上限；429 优先渠道 Retry-After） */
    public interface Backoff {
        Instant nextAttemptAt(int failedAttempts, Instant from, long retryAfterSeconds);
    }

    /** 渠道路由：outbox.channel → 三档渠道实例；未配置 = 确定性 DEAD 而非静默丢弃 */
    public interface ChannelRouter {
        NotificationChannel resolve(String channel);
    }

    public FencedNotifyExecutor(NotifyOutboxStore store, NotificationRenderer renderer,
                                ChannelRouter router, Backoff backoff, Instant clock) {
        this.store = Objects.requireNonNull(store);
        this.renderer = Objects.requireNonNull(renderer);
        this.router = Objects.requireNonNull(router);
        this.backoff = Objects.requireNonNull(backoff);
        this.now = Objects.requireNonNull(clock);
    }

    public Outcome execute(ClaimedNotification notification) {
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
