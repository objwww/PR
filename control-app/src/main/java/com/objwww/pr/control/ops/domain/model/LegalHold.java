package com.objwww.pr.control.ops.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * legal hold（M5-18；V29 legal_hold 行映射）。released_at null = 生效中。
 *
 * <p>scope 语义：族域 = 表名（如 "rca_event"，阻断该表全部分区）；分区域 =
 * "表名:分区名"（如 "rca_event:rca_event_2026_07"）。INV-AM5-9：生效中 hold
 * 阻断一切清理——阻断面在 RetentionService 判定，DB 行是审计权威。
 */
public record LegalHold(
        UUID id,
        String scope,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant releasedAt
) {
    public LegalHold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("scope 不得为空");
        }
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isActive() {
        return releasedAt == null;
    }
}
