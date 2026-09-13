package com.objwww.pr.notify.domain.service;

import com.objwww.pr.notify.domain.channel.NotificationChannel;
import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.port.StaleClaimException;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 * <p>EX-C2a 最长通知期限（RV07 收紧）：attempt 预算之外的第二道墙钟闸——
 * <b>execute 入口即判</b>（超龄行零触网零渲染，首发与重试同受封顶），retry 分支保留
 * 二次检查（防一次长耗时发送跨越边界后再安排旧消息）；DEAD 落
 * notification_deadline_exceeded，原点=notify_outbox.created_at。sent_at=发送
 * <b>确认后</b>时钟取值（发送前取值只是 startedAt，不能准确充当 sentAt）。
 * <p>RV05/RV06：last_error 一律 Jackson 序列化（平台 errmsg 含引号/换行不产生
 * 非法 JSON——非法落账会卡死 CLAIMED 并破坏重试预算）；reason=固定原因码、
 * detail 限长；router 返回 null（未知/已删渠道）是契约面 → channel_not_configured
 * →DEAD，配置删除后的积压行稳定终态，不再靠租约重领空转。
 * 回执语义：机器人 HTTP 接收（errcode=0）只证明机器人收到，不证明值班员阅读。
 */
public final class FencedNotifyExecutor implements NotifySender {

    /** RV05：last_error 序列化唯一出口（手拼 JSON 在含换行/引号的合法错误文本上
     * 产生非法 jsonb，卡死 CLAIMED——禁回退） */
    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
    /** detail 限长（诊断文本非自由存储；现有脱敏/限长纪律同源） */
    static final int MAX_DETAIL_CHARS = 1000;

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

    /** RV05：Jackson 序列化（引号/换行/控制字符天然合法）；reason=固定原因码，
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
    }
}
