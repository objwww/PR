package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.util.Map;
import java.util.Objects;

/**
 * AM 组协议完整映射（webhook.go Message 全字段，§6.1；整组原子落库的载荷）。
 *
 * <p>只映射不拆条——拆组发生在投影期（AlertInboxProcessor）；
 * payloadRaw 是审计唯一权威（bytea），payloadDigest = sha256(payloadRaw)。
 */
public record AlertGroupEnvelope(
        String version,
        String receiver,
        String groupKey,
        Map<String, String> groupLabels,
        Map<String, String> commonLabels,
        Map<String, String> commonAnnotations,
        String externalUrl,
        AlertFiringStatus groupStatus,
        int truncatedAlerts,
        int alertCount,
        byte[] payloadRaw,
        Digest payloadDigest
) {
    public AlertGroupEnvelope {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(groupKey, "groupKey");
        groupLabels = Map.copyOf(groupLabels);
        commonLabels = Map.copyOf(commonLabels);
        commonAnnotations = Map.copyOf(commonAnnotations);
        Objects.requireNonNull(groupStatus, "groupStatus");
        Objects.requireNonNull(payloadRaw, "payloadRaw");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        if (truncatedAlerts < 0 || alertCount < 0) {
            throw new IllegalArgumentException("truncatedAlerts/alertCount 不能为负");
        }
        payloadRaw = payloadRaw.clone();
    }
}
