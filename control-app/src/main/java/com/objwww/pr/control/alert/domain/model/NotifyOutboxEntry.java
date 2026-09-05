package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知 outbox 条目（V9 notify_outbox；§6.5 at-least-once）。
 * 唯一键 (report_id, channel, template_version) 防重；operation_id 进文案使重复可检测。
 * payload 只含白名单渲染字段——不含报告正文/raw（渲染在 notify-app 侧做最终排版）。
 */
public record NotifyOutboxEntry(
        UUID id,
        UUID publicationId,
        UUID reportId,
        String channel,
        String templateVersion,
        UUID operationId,
        String payloadJson,
        OutboxState state,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        String lastError,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt) {

    public NotifyOutboxEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicationId, "publicationId");
        Objects.requireNonNull(reportId, "reportId");
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel 不得为空");
        }
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("templateVersion 不得为空");
        }
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(state, "state");
    }

    /** 生产者出生形态（PENDING，立即可领取） */
    public static NotifyOutboxEntry pending(UUID id, UUID publicationId, UUID reportId,
                                            String channel, String templateVersion,
                                            UUID operationId, String payloadJson, Instant now) {
        return new NotifyOutboxEntry(id, publicationId, reportId, channel, templateVersion,
                operationId, payloadJson, OutboxState.PENDING,
                null, null, 0, 0, 5, now, null, null, now, now);
    }
}
