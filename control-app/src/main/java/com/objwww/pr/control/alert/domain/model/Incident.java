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
 *
 * <p>EX-A4b（F24）：waitingReason = 事故已受理但未调查的显式原因
 * （WAITING_CAPABILITY=路由不意愿 / DEFERRED=背压未录取），null=无等待；
 * 重驱见 IncidentWaitingRedrive（前端与 API 可见"尚未调查"）。
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
        Instant updatedAt,
        String waitingReason
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

    /** EX-A4b 前的 17 参形态（waitingReason=null）——存量调用面与旧测试零改动 */
    public Incident(UUID id, String incidentKey, IncidentStatus status, int generation,
                    Instant episodeStartedAt, Instant lastFiringStartsAt, Instant resolvedAt,
                    Digest lastInvestigationHash, Digest pendingInvestigationHash,
                    long receivedCount, long distinctEventCount, long notificationCount,
                    UUID currentRcaRunId, Instant firstSeenAt, Instant lastEventAt,
                    Instant createdAt, Instant updatedAt) {
        this(id, incidentKey, status, generation, episodeStartedAt, lastFiringStartsAt,
                resolvedAt, lastInvestigationHash, pendingInvestigationHash,
                receivedCount, distinctEventCount, notificationCount, currentRcaRunId,
                firstSeenAt, lastEventAt, createdAt, updatedAt, null);
    }
}
