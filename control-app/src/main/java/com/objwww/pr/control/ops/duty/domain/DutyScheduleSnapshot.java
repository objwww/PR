package com.objwww.pr.control.ops.duty.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 排班快照（M7-12 domain 模型）：解析的全部输入，一不可变值对象。
 *
 * <p>契约⑤（快照陈旧语义）：{@code scheduleVersion} 由管理面任一变更递增、
 * {@code generatedAt}/{@code validUntil} 给消费方（127 adapter 60s 轮询 + 磁盘兜底）
 * 判陈旧用——过期快照仍可解析（值班名册不因快照过期而停摆），但调用方如实记录
 * 「按陈旧快照投递」；损坏/首启无快照走 fallback 通道（不依赖本对象）。
 *
 * <p>{@code channels} 只含 env 键名与元数据——密钥只经 env 注入（INV-AM3-3），
 * 快照序列化面零密钥材料（契约：快照可落盘/可过窄代理）。
 */
public record DutyScheduleSnapshot(
        UUID scheduleId,
        String name,
        long scheduleVersion,
        Instant generatedAt,
        Instant validUntil,
        ZoneId zone,
        RotationMath.Rotation rotation,
        LocalDate anchorDate,
        LocalTime handoffTime,
        List<Layer> layers,
        List<Override> overrides,
        List<Channel> channels) {

    public DutyScheduleSnapshot {
        layers = layers == null ? List.of() : List.copyOf(layers);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    /** 层（layerIndex 小者优先；memberNames 按 position 序） */
    public record Layer(int layerIndex, List<String> memberNames) {
        public Layer {
            memberNames = memberNames == null ? List.of() : List.copyOf(memberNames);
        }
    }

    /** 指定时段顶班（startsAt 含、endsAt 不含；优先级恒高于 layer） */
    public record Override(String memberName, Instant startsAt, Instant endsAt) {
    }

    /** 通道（priority 小者先投；is_fallback 恰一行为 DB 部分唯一索引保证） */
    public record Channel(UUID id, String name, String platform, String envKeyWebhook,
                          String envKeySecret, int priority, boolean isFallback,
                          boolean enabled) {
    }
}
