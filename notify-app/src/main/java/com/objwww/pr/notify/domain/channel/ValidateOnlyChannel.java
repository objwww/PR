package com.objwww.pr.notify.domain.channel;

import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;

import java.util.UUID;

/**
 * VALIDATE_ONLY 档（M3-22 冻结语义：零触网）——渲染器跑通 + payload 契约成立即终点，
 * 绝不发起任何网络调用（验收断言面：VALIDATE_ONLY 全链零网络）。落账 SUPPRESSED，
 * 不冒充 SENT（"dry-run 真实到达"自相矛盾的评审纠正）。
 */
public final class ValidateOnlyChannel implements NotificationChannel {

    @Override
    public SendResult send(RenderedNotification notification, UUID operationId) {
        return new SendResult.Suppressed("validate_only_passed（零触网，未投递）");
    }
}
