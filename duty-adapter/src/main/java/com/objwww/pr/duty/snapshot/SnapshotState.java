package com.objwww.pr.duty.snapshot;

import java.time.Instant;
import java.util.List;

/**
 * 排班快照的 127 侧投影（M7-17）：只取直发所需字段——当班人（消息文案）+ 通道链
 * （env 键名制：URL/密钥仍只在环境变量，快照不含密钥值）。
 *
 * <p>陈旧语义（契约⑤外部腿面）：{@code validUntil} 过期不拒用——外部腿的本职就是
 * 195 挂时也照发；陈旧只记日志，磁盘副本=最后已知好快照。
 */
public record SnapshotState(String onCall, boolean viaFallback, long scheduleVersion,
                            Instant generatedAt, Instant validUntil,
                            List<Channel> channels) {

    /** 通道行（env 键名；secret 键名可空=该通道不加签，如企微） */
    public record Channel(String name, String platform, int priority, boolean fallback,
                          String envKeyWebhook, String envKeySecret) {
    }

    /** 快照是否完全缺席（首启未拉到 + 无磁盘副本） */
    public boolean absent() {
        return channels == null || channels.isEmpty();
    }
}
