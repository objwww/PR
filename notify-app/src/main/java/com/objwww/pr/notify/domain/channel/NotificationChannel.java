package com.objwww.pr.notify.domain.channel;

import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;

import java.util.UUID;

/**
 * 通知渠道（M3-22 三档冻结）：{@code VALIDATE_ONLY}（零触网，渲染与 payload 校验即终点）
 * / {@code TEST_CHANNEL}（真实发送测试机器人）/ {@code LIVE}（正式渠道）。
 * 实现只做平台 body 包装与发送，不解释结果语义之外的内容；分类判定在 {@link SendResult}。
 */
public interface NotificationChannel {

    SendResult send(RenderedNotification notification, UUID operationId);

    /** 发送结局分类（执行器据此落 SENT/RETRY_WAIT/DEAD/SUPPRESSED，不新增第五种） */
    sealed interface SendResult {

        /** 2xx：已投递（at-least-once 的"至少"次） */
        record Delivered() implements SendResult {
        }

        /** 429：渠道限流——Retry-After 缺省 60s；持久化退避不占 worker 槽、不耗预算 */
        record RateLimited(long retryAfterSeconds) implements SendResult {
        }

        /** 5xx / 连接失败（请求未发出）：可重试，耗 attempt 预算 */
        record Retryable(String error) implements SendResult {
        }

        /** 4xx 确定性失败（凭据/参数/权限）：终态 DEAD */
        record Permanent(String error) implements SendResult {
        }

        /** 请求已发出但结果未知（读超时/响应中断）：UNKNOWN 不自动重发 → 终态 DEAD */
        record OutcomeUnknown(String error) implements SendResult {
        }

        /** VALIDATE_ONLY：校验通过、按策略不投递 → SUPPRESSED（诚实落账不冒充 SENT） */
        record Suppressed(String note) implements SendResult {
        }
    }
}
