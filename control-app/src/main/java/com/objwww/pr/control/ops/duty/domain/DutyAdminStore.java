package com.objwww.pr.control.ops.duty.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 值班管理面端口（M7-15；排班/成员/通道 CRUD）。与运行面 {@link DutyStore} 分离：
 * 派发/降级测试假件不承管理面方法；本端口消费者只有管理控制器（operator 授权）。
 *
 * <p>契约⑤：任何排班结构变更（schedule 参数/层/层成员/override）递增
 * schedule_version——快照消费方（127 adapter）按版本判陈旧。
 */
public interface DutyAdminStore {

    /** 成员行（管理面含 id/active） */
    record MemberView(UUID id, String name, String displayName, boolean active) {
    }

    /** 通道行（管理面全字段；env 键名非密钥） */
    record ChannelView(UUID id, String name, String platform, String envKeyWebhook,
                       String envKeySecret, int priority, boolean isFallback,
                       boolean enabled) {
    }

    /** 层行（管理面按 id 编辑） */
    record LayerView(UUID id, int layerIndex, List<UUID> memberIds) {
    }

    /** 排班参数行 */
    record ScheduleView(UUID id, String name, String timezone, String rotation,
                        String anchorDate, String handoffTime, long scheduleVersion) {
    }

    /** override 行（管理面） */
    record OverrideView(UUID id, UUID memberId, Instant startsAt, Instant endsAt,
                        String reason) {
    }

    List<MemberView> listMembers();

    MemberView insertMember(String name, String displayName);

    /** active=false 即软删（台账纪律：不物理删成员） */
    boolean updateMember(UUID id, String displayName, Boolean active);

    List<ChannelView> listChannels();

    ChannelView insertChannel(String name, String platform, String envKeyWebhook,
                              String envKeySecret, int priority, boolean isFallback,
                              boolean enabled);

    /** 空字段 = 不改（部分更新） */
    boolean updateChannel(UUID id, String envKeyWebhook, String envKeySecret,
                          Integer priority, Boolean isFallback, Boolean enabled);

    ScheduleView currentSchedule();

    /** 参数部分更新（空=null 不改）；任何变更递增 schedule_version（契约⑤） */
    boolean updateSchedule(UUID id, String name, String timezone, String rotation,
                           String anchorDate, String handoffTime);

    List<LayerView> listLayers();

    /** 追加层（layer_index = 当前最大+1；成员按序占位 0..n-1）；递增 version */
    LayerView insertLayer(UUID scheduleId, List<UUID> memberIds);

    /** 全量替换层成员（position 重排 0..n-1）；递增 version */
    boolean replaceLayerMembers(UUID layerId, List<UUID> memberIds);

    boolean deleteLayer(UUID layerId);

    List<OverrideView> listOverrides();

    /** 新增 override；递增 version */
    OverrideView insertOverride(UUID scheduleId, UUID memberId, Instant startsAt,
                                Instant endsAt, String reason);

    boolean deleteOverride(UUID id);
}
