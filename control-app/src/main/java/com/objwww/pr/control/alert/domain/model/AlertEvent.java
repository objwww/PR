package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 单条规范化告警（不可变追加；双哈希分离 §6.3）。
 *
 * <p>payloadHash 判"是否处理过同一条"；investigationHash 判"材料是否变化、值不值得重查"。
 * 不做聚合判断——聚合在 IncidentProjector。
 */
public record AlertEvent(
        UUID id,
        UUID inboxId,
        UUID incidentId,
        int generation,
        String fingerprint,
        AlertFiringStatus status,
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant startsAt,
        Instant endsAt,
        Digest payloadHash,
        Digest investigationHash,
        Instant recordedAt
) {
    public AlertEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(inboxId, "inboxId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        labels = Map.copyOf(labels);
        annotations = Map.copyOf(annotations);
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(investigationHash, "investigationHash");
        if (generation < 0) {
            throw new IllegalArgumentException("generation 不能为负");
        }
    }
}
