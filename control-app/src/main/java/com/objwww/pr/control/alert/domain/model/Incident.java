package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 告警聚合态（Keep LastAlert 思路 + 评审 #2 三计数分离）。
 *
 * <p>incidentKey 只含稳定标签（不含告警级别，INV-AM1-4）；
 * episodeStartedAt = episode 水印（§6.7 乱序策略：晚到 resolved 不覆盖更新的 firing）；
 * pendingInvestigationHash = 调查期间收到的材料变化（finishTask rerun 判定输入）。
 */
public record Incident(
        UUID id,
        String incidentKey,
        IncidentStatus status,
        int generation,
        Instant episodeStartedAt,
        Instant lastFiringStartsAt,
        Instant resolvedAt,
        Digest lastInvestigationHash,
        Digest pendingInvestigationHash,
        long receivedCount,
        long distinctEventCount,
        long notificationCount,
        UUID currentRcaRunId,
        Instant firstSeenAt,
        Instant lastEventAt,
        Instant createdAt,
        Instant updatedAt
) {
    public Incident {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(episodeStartedAt, "episodeStartedAt");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastEventAt, "lastEventAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (generation < 0 || receivedCount < 0 || distinctEventCount < 0 || notificationCount < 0) {
            throw new IllegalArgumentException("generation/计数不能为负");
        }
    }
}
