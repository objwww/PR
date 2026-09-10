package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.duty.domain.DutyAdminStore;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.duty.domain.RotationMath;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V43 八表存取（M7-13；control_app 身份）。
 *
 * <p>原子对事务（AM7 §4，仿 BA-53）：先 duty_notification（ON CONFLICT DO NOTHING
 * 撞行回读既有 id）后 duty_delivery（FK 即时检查安全）。幂等全部下推到唯一约束：
 * fingerprint / (notification_id, priority)。游标分页 = created_at 严格降序 +
 * id 兜底同刻行（列表面稳定翻页）。
 */
public class PostgresDutyStore implements DutyStore, DutyAdminStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresDutyStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ 快照

    @Override
    public DutyScheduleSnapshot loadSnapshot() {
        record ScheduleRow(UUID id, String name, long version, Instant updatedAt, ZoneId zone,
                           RotationMath.Rotation rotation, LocalDate anchor, LocalTime handoff) {
        }
        List<ScheduleRow> schedules = jdbc.sql("""
                select id, name, schedule_version, updated_at, timezone, rotation,
                       anchor_date, handoff_time
                from duty_schedule order by updated_at desc limit 1
                """).query((rs, i) -> new ScheduleRow(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getLong("schedule_version"), ts(rs, "updated_at"),
                ZoneId.of(rs.getString("timezone")),
                RotationMath.Rotation.valueOf(rs.getString("rotation")),
                rs.getObject("anchor_date", LocalDate.class),
                rs.getObject("handoff_time", LocalTime.class))).list();
        if (schedules.isEmpty()) {
            return emptySnapshot();
        }
        ScheduleRow s = schedules.get(0);
        Instant now = Instant.now();

        Map<Integer, List<String>> layerMembers = new LinkedHashMap<>();
        // B-57：JdbcClient .query(mapper) 不带终结操作（.list() 等）= 死语句——SQL 根本
        // 不执行，layerMembers 恒空 → onCall 恒 null → viaFallback → 派发恒 NO_CHANNEL
        // （195 首跑 L2 暴露；本地 UT 用假 store 不可见——真 PG 守卫=PostgresDutyStoreTest）
        jdbc.sql("""
                select l.layer_index, m.name
                from duty_layer l
                join duty_layer_member lm on lm.layer_id = l.id
                join duty_member m on m.id = lm.member_id
                where l.schedule_id = :sid and m.active
                order by l.layer_index, lm.position
                """).param("sid", s.id()).query((rs, i) -> {
            layerMembers.computeIfAbsent(rs.getInt("layer_index"), k -> new ArrayList<>())
                    .add(rs.getString("name"));
            return null;
        }).list();
        List<DutyScheduleSnapshot.Layer> layers = layerMembers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DutyScheduleSnapshot.Layer(e.getKey(), e.getValue()))
                .toList();

        List<DutyScheduleSnapshot.Override> overrides = jdbc.sql("""
                select m.name, o.starts_at, o.ends_at
                from duty_override o join duty_member m on m.id = o.member_id
                where o.schedule_id = :sid and o.ends_at > now()
                order by o.starts_at
                """).param("sid", s.id()).query((rs, i) -> new DutyScheduleSnapshot.Override(
                rs.getString("name"), ts(rs, "starts_at"), ts(rs, "ends_at"))).list();

        List<DutyScheduleSnapshot.Channel> channels = jdbc.sql("""
                select id, name, platform, env_key_webhook, env_key_secret,
                       priority, is_fallback, enabled
                from duty_channel where enabled order by priority
                """).query((rs, i) -> new DutyScheduleSnapshot.Channel(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("platform"), rs.getString("env_key_webhook"),
                rs.getString("env_key_secret"), rs.getInt("priority"),
                rs.getBoolean("is_fallback"), rs.getBoolean("enabled"))).list();

        return new DutyScheduleSnapshot(s.id(), s.name(), s.version(), s.updatedAt(),
                now.plusSeconds(90), s.zone(), s.rotation(), s.anchor(), s.handoff(),
                layers, overrides, channels);
    }

    private static DutyScheduleSnapshot emptySnapshot() {
        Instant now = Instant.now();
        return new DutyScheduleSnapshot(UUID.randomUUID(), "(none)", 0L, now,
                now.plusSeconds(90), ZoneId.of("Asia/Shanghai"),
                RotationMath.Rotation.DAILY, LocalDate.EPOCH, LocalTime.MIDNIGHT,
                List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------ 派发

    @Override
    public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                               DutyScheduleSnapshot.Channel channel) {
        return tx.execute(status -> {
            UUID id = jdbc.sql("""
                    insert into duty_notification (id, source, episode_id, alert_fingerprint,
                        event_status, severity, title, body_text, payload_json, fingerprint,
                        status, created_at)
                    values (:id, :source, :episode, :afp, :event, :severity, :title, :body,
                        cast(:payload as jsonb), :fingerprint, 'UNREAD', now())
                    on conflict (fingerprint) do nothing
                    returning id
                    """).param("id", UUID.randomUUID())
                    .param("source", n.source())
                    .param("episode", n.episodeId())
                    .param("afp", n.alertFingerprint())
                    .param("event", n.eventStatus())
                    .param("severity", n.severity())
                    .param("title", n.title())
                    .param("body", n.bodyText())
                    .param("payload", n.payloadJson())
                    .param("fingerprint", n.fingerprint())
                    .query(UUID.class).optional().orElse(null);
            boolean created = id != null;
            if (!created) {
                id = jdbc.sql("select id from duty_notification where fingerprint = :fp")
                        .param("fp", n.fingerprint()).query(UUID.class).single();
            }
            if (channel != null) {
                insertDelivery(id, channel, 0);
            }
            return new DispatchOutcome(id, created, channel == null ? null : channel.name());
        });
    }

    @Override
    public boolean insertDeliveryIfAbsent(UUID notificationId, DutyScheduleSnapshot.Channel channel) {
        return insertDelivery(notificationId, channel, 0) == 1;
    }

    @Override
    public DispatchOutcome insertExternalNotification(NewNotification n) {
        UUID id = jdbc.sql("""
                insert into duty_notification (id, source, episode_id, alert_fingerprint,
                    event_status, severity, title, body_text, payload_json, fingerprint,
                    status, created_at)
                values (:id, :source, :episode, :afp, :event, :severity, :title, :body,
                    cast(:payload as jsonb), :fingerprint, 'UNREAD', now())
                on conflict (fingerprint) do nothing
                returning id
                """).param("id", UUID.randomUUID())
                .param("source", n.source())
                .param("episode", n.episodeId())
                .param("afp", n.alertFingerprint())
                .param("event", n.eventStatus())
                .param("severity", n.severity())
                .param("title", n.title())
                .param("body", n.bodyText())
                .param("payload", n.payloadJson())
                .param("fingerprint", n.fingerprint())
                .query(UUID.class).optional().orElse(null);
        if (id != null) {
            return new DispatchOutcome(id, true, null);
        }
        UUID existing = jdbc.sql("select id from duty_notification where fingerprint = :fp")
                .param("fp", n.fingerprint()).query(UUID.class).single();
        return new DispatchOutcome(existing, false, null);
    }

    /** 补投/首投共用：ON CONFLICT (notification_id, priority) DO NOTHING */
    private int insertDelivery(UUID notificationId, DutyScheduleSnapshot.Channel channel,
                               int attemptCount) {
        return jdbc.sql("""
                insert into duty_delivery (id, notification_id, channel_id, priority, state,
                    lease_epoch, attempt_count, max_attempts, available_at, created_at, updated_at)
                values (:id, :nid, :cid, :priority, 'PENDING', 0, :att, 5, now(), now(), now())
                on conflict (notification_id, priority) do nothing
                """).param("id", UUID.randomUUID())
                .param("nid", notificationId)
                .param("cid", channel.id())
                .param("priority", channel.priority())
                .param("att", attemptCount)
                .update();
    }

    // ------------------------------------------------------------------ watcher / 查询

    @Override
    public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
        return jdbc.sql("""
                select d.id, d.notification_id, d.priority, n.created_at as n_created
                from duty_delivery d join duty_notification n on n.id = d.notification_id
                where d.state = 'DEAD' and d.updated_at >= :since
                """).param("since", Timestamp.from(since))
                .query((rs, i) -> new DeadDelivery(
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getInt("priority"), ts(rs, "n_created"))).list();
    }

    @Override
    public boolean markRead(UUID notificationId, Instant readAt) {
        return jdbc.sql("""
                update duty_notification set status = 'READ', read_at = :readAt
                where id = :id and status = 'UNREAD'
                """).param("readAt", Timestamp.from(readAt))
                .param("id", notificationId)
                .update() == 1;
    }

    @Override
    public List<DutyNotificationView> listNotifications(String cursor, int limit,
                                                        boolean unreadOnly) {
        StringBuilder sql = new StringBuilder("""
                select id, source, episode_id, event_status, severity, title, status, created_at
                from duty_notification
                """);
        Set<String> where = new HashSet<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lim", limit + 1);
        if (unreadOnly) {
            where.add("status = 'UNREAD'");
        }
        if (cursor != null && !cursor.isBlank()) {
            where.add("(created_at, id) < (cast(:cursorAt as timestamptz), cast(:cursorId as uuid))");
            String[] parts = cursor.split("/", 2);
            params.put("cursorAt", parts[0]);
            params.put("cursorId", parts[1]);
        }
        if (!where.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", where));
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        List<DutyNotificationView> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, i) -> new DutyNotificationView(
                        rs.getObject("id", UUID.class), rs.getString("source"),
                        rs.getString("episode_id"), rs.getString("event_status"),
                        rs.getString("severity"), rs.getString("title"),
                        rs.getString("status"), ts(rs, "created_at"))).list();
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    @Override
    public long unreadCount() {
        return jdbc.sql("select count(*) from duty_notification where status = 'UNREAD'")
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ 群模拟 feed

    @Override
    public List<NotificationFeedView> listFeed(String cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, source, episode_id, event_status, severity, title, body_text,
                       status, created_at
                from duty_notification
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lim", limit + 1);
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" where (created_at, id) < (cast(:cursorAt as timestamptz),"
                    + " cast(:cursorId as uuid))");
            String[] parts = cursor.split("/", 2);
            params.put("cursorAt", parts[0]);
            params.put("cursorId", parts[1]);
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        List<NotificationFeedView> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, i) -> new NotificationFeedView(
                        rs.getObject("id", UUID.class), rs.getString("source"),
                        rs.getString("episode_id"), rs.getString("event_status"),
                        rs.getString("severity"), rs.getString("title"),
                        rs.getString("body_text"), rs.getString("status"),
                        ts(rs, "created_at"))).list();
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    @Override
    public Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return Map.of();
        }
        // drill 推导（诚实面）：D01 替身期通道名/env 键名自带 echo/test 字样；
        // 只读推导不写库——真机器人接管后配置变更自然翻 false
        List<DeliveryRow> rows = jdbc.sql("""
                select d.notification_id, c.name, c.platform, d.priority, d.state,
                       d.attempt_count, d.sent_at, d.last_error::text as last_error,
                       (lower(c.name) like '%echo%' or lower(c.name) like '%test%'
                        or upper(c.env_key_webhook) like '%TEST%') as drill
                from duty_delivery d join duty_channel c on c.id = d.channel_id
                where d.notification_id in (:ids)
                order by d.notification_id, d.priority
                """).param("ids", notificationIds)
                .query((rs, i) -> new DeliveryRow(
                        rs.getObject("notification_id", UUID.class),
                        new DeliveryView(rs.getString("name"), rs.getString("platform"),
                                rs.getInt("priority"), rs.getString("state"),
                                rs.getInt("attempt_count"), ts(rs, "sent_at"),
                                truncate(rs.getString("last_error"), 300),
                                rs.getBoolean("drill")))).list();
        Map<UUID, List<DeliveryView>> grouped = new LinkedHashMap<>();
        for (DeliveryRow row : rows) {
            grouped.computeIfAbsent(row.notificationId(), k -> new ArrayList<>())
                    .add(row.view());
        }
        return grouped;
    }

    private record DeliveryRow(UUID notificationId, DeliveryView view) {
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    // ------------------------------------------------------------------ 管理面（M7-15）

    @Override
    public List<MemberView> listMembers() {
        return jdbc.sql("""
                select id, name, display_name, active from duty_member order by name
                """).query((rs, i) -> new MemberView(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("display_name"),
                rs.getBoolean("active"))).list();
    }

    @Override
    public MemberView insertMember(String name, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into duty_member(id, name, display_name, active, created_at, updated_at)
                values (:id, :name, :display, true, now(), now())
                """).param("id", id).param("name", name).param("display", displayName)
                .update();
        return new MemberView(id, name, displayName, true);
    }

    @Override
    public boolean updateMember(UUID id, String displayName, Boolean active) {
        return jdbc.sql("""
                update duty_member set
                    display_name = coalesce(:display, display_name),
                    active = coalesce(:active, active),
                    updated_at = now()
                where id = :id
                """).param("display", displayName).param("active", active)
                .param("id", id).update() == 1;
    }

    @Override
    public List<ChannelView> listChannels() {
        return jdbc.sql("""
                select id, name, platform, env_key_webhook, env_key_secret,
                       priority, is_fallback, enabled
                from duty_channel order by priority
                """).query((rs, i) -> new ChannelView(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("platform"), rs.getString("env_key_webhook"),
                rs.getString("env_key_secret"), rs.getInt("priority"),
                rs.getBoolean("is_fallback"), rs.getBoolean("enabled"))).list();
    }

    @Override
    public ChannelView insertChannel(String name, String platform, String envKeyWebhook,
                                     String envKeySecret, int priority, boolean isFallback,
                                     boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into duty_channel(id, name, platform, env_key_webhook, env_key_secret,
                    priority, is_fallback, enabled, created_at, updated_at)
                values (:id, :name, :platform, :webhook, :secret, :priority, :fallback,
                    :enabled, now(), now())
                """).param("id", id).param("name", name).param("platform", platform)
                .param("webhook", envKeyWebhook).param("secret", envKeySecret)
                .param("priority", priority).param("fallback", isFallback)
                .param("enabled", enabled).update();
        return new ChannelView(id, name, platform, envKeyWebhook, envKeySecret, priority,
                isFallback, enabled);
    }

    @Override
    public boolean updateChannel(UUID id, String envKeyWebhook, String envKeySecret,
                                 Integer priority, Boolean isFallback, Boolean enabled) {
        return jdbc.sql("""
                update duty_channel set
                    env_key_webhook = coalesce(:webhook, env_key_webhook),
                    env_key_secret = coalesce(:secret, env_key_secret),
                    priority = coalesce(:priority, priority),
                    is_fallback = coalesce(:fallback, is_fallback),
                    enabled = coalesce(:enabled, enabled),
                    updated_at = now()
                where id = :id
                """).param("webhook", envKeyWebhook).param("secret", envKeySecret)
                .param("priority", priority).param("fallback", isFallback)
                .param("enabled", enabled).param("id", id).update() == 1;
    }

    @Override
    public ScheduleView currentSchedule() {
        List<ScheduleView> rows = jdbc.sql("""
                select id, name, timezone, rotation, anchor_date, handoff_time, schedule_version
                from duty_schedule order by updated_at desc limit 1
                """).query((rs, i) -> new ScheduleView(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("timezone"), rs.getString("rotation"),
                String.valueOf(rs.getObject("anchor_date", LocalDate.class)),
                String.valueOf(rs.getObject("handoff_time", LocalTime.class)),
                rs.getLong("schedule_version"))).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean updateSchedule(UUID id, String name, String timezone, String rotation,
                                  String anchorDate, String handoffTime) {
        int updated = jdbc.sql("""
                update duty_schedule set
                    name = coalesce(:name, name),
                    timezone = coalesce(:tz, timezone),
                    rotation = coalesce(:rotation, rotation),
                    anchor_date = coalesce(cast(:anchor as date), anchor_date),
                    handoff_time = coalesce(cast(:handoff as time), handoff_time),
                    schedule_version = schedule_version + 1,
                    updated_at = now()
                where id = :id
                """).param("name", name).param("tz", timezone).param("rotation", rotation)
                .param("anchor", anchorDate).param("handoff", handoffTime)
                .param("id", id).update();
        return updated == 1;
    }

    @Override
    public List<LayerView> listLayers() {
        List<LayerView> raw = jdbc.sql("""
                select l.id, l.layer_index, lm.member_id, lm.position
                from duty_layer l left join duty_layer_member lm on lm.layer_id = l.id
                order by l.layer_index, lm.position
                """).query((rs, i) -> new LayerView(rs.getObject("id", UUID.class),
                rs.getInt("layer_index"),
                rs.getObject("member_id", UUID.class) == null ? List.<UUID>of()
                        : List.of(rs.getObject("member_id", UUID.class)))).list();
        Map<UUID, LayerView> merged = new LinkedHashMap<>();
        for (LayerView v : raw) {
            LayerView cur = merged.get(v.id());
            merged.put(v.id(), cur == null ? v
                    : new LayerView(v.id(), v.layerIndex(),
                    concat(cur.memberIds(), v.memberIds())));
        }
        return List.copyOf(merged.values());
    }

    private static List<UUID> concat(List<UUID> a, List<UUID> b) {
        List<UUID> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    @Override
    public LayerView insertLayer(UUID scheduleId, List<UUID> memberIds) {
        UUID layerId = UUID.randomUUID();
        jdbc.sql("""
                insert into duty_layer(id, schedule_id, layer_index, created_at)
                values (:id, :sid,
                    coalesce((select max(layer_index) + 1 from duty_layer
                              where schedule_id = :sid), 0), now())
                """).param("id", layerId).param("sid", scheduleId).update();
        insertLayerMembers(layerId, memberIds);
        bumpVersion(scheduleId);
        int index = jdbc.sql("select layer_index from duty_layer where id = :id")
                .param("id", layerId).query(Integer.class).single();
        return new LayerView(layerId, index, List.copyOf(memberIds));
    }

    @Override
    public boolean replaceLayerMembers(UUID layerId, List<UUID> memberIds) {
        UUID scheduleId = jdbc.sql(
                        "select schedule_id from duty_layer where id = :id")
                .param("id", layerId).query(UUID.class).optional().orElse(null);
        if (scheduleId == null) {
            return false;
        }
        jdbc.sql("delete from duty_layer_member where layer_id = :lid")
                .param("lid", layerId).update();
        insertLayerMembers(layerId, memberIds);
        bumpVersion(scheduleId);
        return true;
    }

    private void insertLayerMembers(UUID layerId, List<UUID> memberIds) {
        int pos = 0;
        for (UUID memberId : memberIds) {
            jdbc.sql("""
                    insert into duty_layer_member(layer_id, member_id, position)
                    values (:lid, :mid, :pos)
                    """).param("lid", layerId).param("mid", memberId)
                    .param("pos", pos++).update();
        }
    }

    @Override
    public boolean deleteLayer(UUID layerId) {
        UUID scheduleId = jdbc.sql(
                        "select schedule_id from duty_layer where id = :id")
                .param("id", layerId).query(UUID.class).optional().orElse(null);
        if (scheduleId == null) {
            return false;
        }
        jdbc.sql("delete from duty_layer_member where layer_id = :lid")
                .param("lid", layerId).update();
        jdbc.sql("delete from duty_layer where id = :id").param("id", layerId).update();
        bumpVersion(scheduleId);
        return true;
    }

    @Override
    public List<OverrideView> listOverrides() {
        return jdbc.sql("""
                select id, member_id, starts_at, ends_at, reason
                from duty_override order by starts_at
                """).query((rs, i) -> new OverrideView(rs.getObject("id", UUID.class),
                rs.getObject("member_id", UUID.class), ts(rs, "starts_at"),
                ts(rs, "ends_at"), rs.getString("reason"))).list();
    }

    @Override
    public OverrideView insertOverride(UUID scheduleId, UUID memberId, Instant startsAt,
                                       Instant endsAt, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into duty_override(id, schedule_id, member_id, starts_at, ends_at,
                    reason, created_at)
                values (:id, :sid, :mid, :startsAt, :endsAt, :reason, now())
                """).param("id", id).param("sid", scheduleId).param("mid", memberId)
                .param("startsAt", Timestamp.from(startsAt))
                .param("endsAt", Timestamp.from(endsAt)).param("reason", reason).update();
        bumpVersion(scheduleId);
        return new OverrideView(id, memberId, startsAt, endsAt, reason);
    }

    @Override
    public boolean deleteOverride(UUID id) {
        return jdbc.sql("delete from duty_override where id = :id")
                .param("id", id).update() == 1;
    }

    private void bumpVersion(UUID scheduleId) {
        jdbc.sql("""
                update duty_schedule set schedule_version = schedule_version + 1,
                    updated_at = now()
                where id = :id
                """).param("id", scheduleId).update();
    }
}
