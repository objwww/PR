package com.objwww.pr.control.ops.duty.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 值班存储端口（M7-13；control_app 身份的读写面）。
 *
 * <p>事务边界：{@link #insertNotificationWithFirstDelivery} 是唯一多行写——
 * duty_notification + 首行 duty_delivery 同一事务落库（AM7 §4 原子对，仿 BA-53：
 * 先 notification 后 delivery，FK 即时检查安全）；其余方法各自单语句。
 * 幂等：两 insert 均_ON CONFLICT DO NOTHING_——AM 重发/派发重试不产生重复行，
 * 唯一约束（fingerprint / (notification_id, priority)）是并发安全的最后防线。
 */
public interface DutyStore {

    /** 新值班通知（领域字段全集；fingerprint 已由 {@link DutyAlertIdentity} 铸造） */
    record NewNotification(String source, String episodeId, String alertFingerprint,
                           String eventStatus, String severity, String title,
                           String bodyText, String payloadJson, String fingerprint) {
    }

    /** 派发结局：created=false 表示 fingerprint 撞已存行（重发去重，非错误） */
    record DispatchOutcome(UUID notificationId, boolean created, String channelName) {
    }

    /** 降级扫描输入行（DEAD 终态行 + 通知年龄闸所需列） */
    record DeadDelivery(UUID notificationId, UUID deliveryId, int priority,
                        Instant notificationCreatedAt) {
    }

    DutyScheduleSnapshot loadSnapshot();

    /** 原子对：通知 + 首优先级通道行（channel 可空=空链诚实记账，只落通知行） */
    DispatchOutcome insertNotificationWithFirstDelivery(NewNotification notification,
                                                        DutyScheduleSnapshot.Channel channel);

    /**
     * 127 duty-adapter 回写面（M7-17）：只落台账行（GATUS 腿投递由 127 直发，
     * 195 侧无 delivery 行）。_ON CONFLICT DO NOTHING_——spool 重放/事件重发去重。
     */
    DispatchOutcome insertExternalNotification(NewNotification notification);

    /** 降级补投行铸造（_ON CONFLICT DO NOTHING_；返回 false=槽位已被占） */
    boolean insertDeliveryIfAbsent(UUID notificationId, DutyScheduleSnapshot.Channel channel);

    /** 窗口内进 DEAD 的投递行（watcher 扫描面；重复扫描无害——DEAD 是终态） */
    List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since);

    /** 已读 CAS：UNREAD→READ 影响行数=1 才算翻转（契约④：只翻状态，无接单语义） */
    boolean markRead(UUID notificationId, Instant readAt);

    /** 消息列表（游标分页，created_at desc；cursor=null 首页） */
    List<DutyNotificationView> listNotifications(String cursor, int limit, boolean unreadOnly);

    long unreadCount();

    /** 列表行投影（不含 payload_json 全文，列表面轻量） */
    record DutyNotificationView(UUID id, String source, String episodeId,
                                String eventStatus, String severity, String title,
                                String status, Instant createdAt) {
    }

    /**
     * 群模拟 feed 行（站内聊天面）：比 {@link DutyNotificationView} 多 body_text——
     * 气泡正文的真实来源（不重新渲染，落库什么就显示什么）。
     */
    record NotificationFeedView(UUID id, String source, String episodeId,
                                String eventStatus, String severity, String title,
                                String bodyText, String status, Instant createdAt) {
    }

    /**
     * 投递行投影（气泡的送达面）。drill=演练通道标记：通道名含 echo/test 或
     * env_key_webhook 键名含 TEST 即真——D01 替身期（echo-bot/K_DUTY_TEST_*）如实
     * 标记，不伪造送达对象；真机器人上线后该推导随通道配置自然翻 false。
     */
    record DeliveryView(String channelName, String platform, int priority, String state,
                        int attemptCount, Instant sentAt, String lastError, boolean drill) {
    }

    /** 群模拟 feed（游标分页语义同 {@link #listNotifications}，不含未读过滤） */
    List<NotificationFeedView> listFeed(String cursor, int limit);

    /**
     * 一批通知的投递行（按 priority 升序归组）。空组=诚实语义：GATUS 腿由 127
     * 直发回写只落台账行（195 无投递行），或派发时通道链为空——前端据 source
     * 与空组区分标注，不许补造。
     */
    Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> notificationIds);
}
