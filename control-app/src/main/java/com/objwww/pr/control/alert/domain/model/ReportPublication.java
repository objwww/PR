package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告发布记录（V9 report_publication；M3-09 状态机冻结）。
 * 一报告一发布记录；渠道维度展开在 notify_outbox（publication : outbox = 1 : N）。
 */
public record ReportPublication(
        UUID id,
        UUID reportId,
        PublicationState state,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public ReportPublication {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(state, "state");
        if (attemptCount < 0 || maxAttempts < 1 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attemptCount/maxAttempts 非法");
        }
    }

    /** 生产者出生形态（报告就绪同事务写入；readyAt = outbox 可领取时刻） */
    public static ReportPublication ready(UUID id, UUID reportId, Instant readyAt) {
        return new ReportPublication(id, reportId, PublicationState.READY,
                null, null, 0, 0, 5, readyAt, null, readyAt, readyAt);
    }

    public ReportPublication withState(PublicationState next, String lastError, Instant now) {
        return new ReportPublication(id, reportId, next, leaseOwner, leaseUntil, leaseEpoch,
                attemptCount, maxAttempts, availableAt, lastError, createdAt, now);
    }
}
