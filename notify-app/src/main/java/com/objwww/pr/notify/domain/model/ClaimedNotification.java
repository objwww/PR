package com.objwww.pr.notify.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 领取到的通知行（notify_outbox 一行 = 一个 (report, channel, template) 投递单元）。
 *
 * <p>唯一键 (report_id, channel, template_version) 在库侧防重（不靠随机 UUID）；
 * operation_id 贯穿全部渠道行——重复投递可检测可审计（INV-AM3-2 at-least-once）。
 * payload 只含生产方白名单摘录（不含报告正文），渲染排版在本模块。
 * createdAt（EX-C2a）：行铸造时刻，最长通知期限（墙钟闸）的计量原点。
 */
public record ClaimedNotification(UUID id,
                                  UUID publicationId,
                                  UUID reportId,
                                  String channel,
                                  String templateVersion,
                                  UUID operationId,
                                  String payloadJson,
                                  int attemptCount,
                                  int maxAttempts,
                                  long leaseEpoch,
                                  Instant createdAt) {

    public ClaimedNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("max_attempts 必须为正");
        }
    }
}
