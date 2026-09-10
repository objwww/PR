package com.objwww.pr.control.ops.duty.domain;

import com.objwww.pr.shared.Digests;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 值班通知事件身份（M7-12/13；评审契约①的铸造面，纯函数）：
 * <ul>
 *   <li>alertFingerprint = sha256(资源身份)——commonLabels（含 instance/resource 级
 *       label）+ groupKey 规范化摘要。<b>分钟桶不参与</b>：同一实例的故障与恢复、
 *       重发的同组告警恒同指纹；不同实例（labels 不同）即使同分钟也不同指纹——
 *       「分钟桶合并异实例故障」的评审病灶即在此消除；</li>
 *   <li>episodeId = sha256(alertFingerprint + startsAt 分钟桶)——firing 与 resolved
 *       共享同一 startsAt（AM 协议），故同剧集；跨次故障（startsAt 不同）新剧集；</li>
 *   <li>fingerprint = sha256(source + alertFingerprint + episodeId + eventStatus)——
 *       去重键：firing 重发去重为一行，resolved 独立成行不被故障行吞掉。</li>
 * </ul>
 */
public record DutyAlertIdentity(String alertFingerprint, String episodeId, String fingerprint) {

    public static DutyAlertIdentity of(String source, Map<String, String> commonLabels,
                                       String groupKey, Instant startsAt, String eventStatus) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(eventStatus);
        Map<String, String> canonical = new TreeMap<>(commonLabels == null
                ? Map.of() : commonLabels);
        StringBuilder identity = new StringBuilder();
        canonical.forEach((k, v) -> identity.append(k).append('=').append(v).append('\n'));
        if (groupKey != null) {
            identity.append("#groupKey=").append(groupKey).append('\n');
        }
        String alertFingerprint = Digests.sha256Hex(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String episodeId = Digests.sha256Hex((alertFingerprint + '|'
                + Objects.requireNonNull(startsAt).truncatedTo(ChronoUnit.MINUTES))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fingerprint = Digests.sha256Hex((source + '|' + alertFingerprint + '|'
                + episodeId + '|' + eventStatus)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new DutyAlertIdentity(alertFingerprint, episodeId, fingerprint);
    }
}
