package com.objwww.pr.control.ops.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 保留策略（M5-18；V29 retention_policy 行映射，insert-only 版本链——最新
 * policy_version 即生效策略）。
 *
 * <p>hot_retention 以整天数计（月分区归档粒度下 day 级即契约精度；C-23④）。
 * legal_hold 布尔为策略面标志，行级阻断语义在 legal_hold 表（released_at null = 生效中）。
 */
public record RetentionPolicy(
        UUID id,
        int policyVersion,
        long hotRetentionDays,
        String coldLocation,
        boolean legalHold,
        Instant createdAt
) {
    public RetentionPolicy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policyVersion 从 1 起");
        }
        if (hotRetentionDays < 1) {
            throw new IllegalArgumentException("hotRetentionDays 从 1 起");
        }
        if (coldLocation == null || coldLocation.isBlank()) {
            throw new IllegalArgumentException("coldLocation 不得为空");
        }
    }
}
