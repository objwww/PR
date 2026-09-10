-- ============================================================================
-- V43 —— AM7 值班三件套（M7-11；docs/告警AM7-值班通知与值班表增量技术方案.md §2/§4）
--   值班表（发给谁）→ webhook 通道链（怎么发）→ 消息列表（台账与站内留痕）八表。
--
-- 评审六契约的 schema 落点：
--   ①事件身份：duty_notification = alert_fingerprint(资源身份摘要) + episode_id
--     (fingerprint+startsAt 分钟桶，firing/resolved 同剧集结) + fingerprint
--     (source+alert_fingerprint+episode_id+event_status) 唯一去重——恢复通知
--     独立成行，不被故障通知去重吞掉；
--   ③降级并发：duty_delivery unique(notification_id, priority)——一通知一优先级
--     槽，watcher 条件推进补投下一槽，结构性无并发重复；
--   ④已读≠接单：status 只 UNREAD/READ（无 ACK 态），已读 CAS 不触碰 operator_case；
--   ⑤独立应急通道：duty_channel.is_fallback 部分唯一索引钉死恰一行，通道凭 env
--     键名解析（不依赖排班数据，两腿全挂时仍是活路）。
--
-- 密钥纪律（INV-AM3-3）：duty_channel 只存 env 键名（env_key_webhook/_secret），
--   webhook URL/加签 secret 只经环境注入，不落库/不落日志/不进前端。
--
-- 回滚（一语句块，依赖倒序）：
--   drop table duty_delivery, duty_notification, duty_override,
--     duty_layer_member, duty_layer, duty_schedule, duty_channel, duty_member;
-- ============================================================================

-- ---------- 1. duty_member：值班成员（O-4 未落地前的名字实体，非系统用户） ----------
create table duty_member (
    id           uuid primary key,
    name         text not null unique,
    display_name text,
    active       boolean not null default true,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

-- ---------- 2. duty_channel：通道链（优先级小者先投；fallback 恰一行） ----------
create table duty_channel (
    id              uuid primary key,
    name            text not null unique,
    platform        text not null check (platform in ('DINGTALK','WECOM')),
    env_key_webhook text not null,
    env_key_secret  text,                       -- 钉钉加签 env 键名，可空；企微不适用
    priority        integer not null check (priority >= 1),
    is_fallback     boolean not null default false,
    enabled         boolean not null default true,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,

    constraint uq_duty_channel_priority unique (priority)
);

-- 契约⑤：fallback 通道恰一行（true 行上 is_fallback 唯一）
create unique index uq_duty_channel_one_fallback on duty_channel (is_fallback)
    where is_fallback;

-- ---------- 3. duty_schedule：排班（anchor+handoff 无状态轮换，§7 抄 OnCall 模型） ----------
create table duty_schedule (
    id               uuid primary key,
    name             text not null unique,
    timezone         text not null default 'Asia/Shanghai',
    rotation         text not null check (rotation in ('DAILY','WEEKLY')),
    anchor_date      date not null,
    handoff_time     time not null default '09:00',
    schedule_version bigint not null default 1, -- 契约⑤：快照陈旧判定锚（管理面变更时递增）
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

-- ---------- 4. duty_layer：层（layer_index 小者优先；高层覆盖低层） ----------
create table duty_layer (
    id          uuid primary key,
    schedule_id uuid not null references duty_schedule(id),
    layer_index integer not null check (layer_index >= 0),
    created_at  timestamptz not null,

    constraint uq_duty_layer unique (schedule_id, layer_index)
);

-- ---------- 5. duty_layer_member：层内轮换位（position=轮换序） ----------
create table duty_layer_member (
    layer_id  uuid not null references duty_layer(id),
    member_id uuid not null references duty_member(id),
    position  integer not null check (position >= 0),

    primary key (layer_id, member_id),
    constraint uq_duty_layer_member_position unique (layer_id, position)
);

-- ---------- 6. duty_override：指定时段顶班（优先级恒高于 layer） ----------
create table duty_override (
    id          uuid primary key,
    schedule_id uuid not null references duty_schedule(id),
    member_id   uuid not null references duty_member(id),
    starts_at   timestamptz not null,
    ends_at     timestamptz not null,
    reason      text,
    created_at  timestamptz not null,

    constraint ck_duty_override_window check (ends_at > starts_at)
);

create index ix_duty_override_window
    on duty_override (schedule_id, starts_at, ends_at);

-- ---------- 7. duty_notification：消息列表台账（只增不改终态列；行落库=站内已投递） ----------
create table duty_notification (
    id                uuid primary key,
    source            text not null check (source in ('RCA_SYSTEM','MANUAL','GATUS')),
    episode_id        text not null,   -- 契约①：同剧集 firing/resolved 关联锚
    alert_fingerprint text not null,   -- 契约①：资源身份摘要（labels 含 instance 级）
    event_status      text not null check (event_status in ('firing','resolved')),
    severity          text,
    title             text not null,
    body_text         text not null,
    payload_json      jsonb,
    fingerprint       text not null,   -- 去重键=source+alert_fingerprint+episode_id+event_status
    status            text not null default 'UNREAD'
                      check (status in ('UNREAD','READ')),   -- 契约④：无 ACK 态
    read_at           timestamptz,
    created_at        timestamptz not null,

    constraint uq_duty_notification_dedup unique (fingerprint),
    constraint ck_duty_notification_read check ((status = 'READ') = (read_at is not null))
);

create index ix_duty_notification_list on duty_notification (created_at desc);
-- 契约①：剧集视图（同 episode 的 firing→resolved 演进可查）
create index ix_duty_notification_episode on duty_notification (episode_id, created_at);

-- ---------- 8. duty_delivery：投递行（六态照搬 notify_outbox 范式；表分离不放松 V9） ----------
-- 与 notify_outbox 的差异：无 publication/report 绑定（值班通知无报告可绑）；
-- 槽位 = (notification_id, priority)——降级 = 补投下一优先级行（control_app watcher 铸造）。
create table duty_delivery (
    id              uuid primary key,
    notification_id uuid not null references duty_notification(id),
    channel_id      uuid not null references duty_channel(id),
    priority        integer not null,
    state           varchar(16) not null,
    lease_owner     text,
    lease_until     timestamptz,
    lease_epoch     bigint not null default 0,
    attempt_count   integer not null default 0,
    max_attempts    integer not null default 5,
    available_at    timestamptz not null default now(),
    last_error      jsonb,
    sent_at         timestamptz,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,

    constraint uq_duty_delivery_slot unique (notification_id, priority),
    constraint ck_duty_delivery_state
        check (state in ('PENDING','CLAIMED','SENT','RETRY_WAIT','DEAD','SUPPRESSED')),
    constraint ck_duty_delivery_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
    constraint ck_duty_delivery_lifecycle
        check ((state = 'SENT') = (sent_at is not null))
);

-- 领取：公平排序（V9 同原则）；duty 与 notify_outbox 共享单 worker 串行预算
create index ix_duty_delivery_claim on duty_delivery (available_at, created_at)
    where state in ('PENDING','RETRY_WAIT');
-- 崩溃回收：租约过期的 CLAIMED 可被重领
create index ix_duty_delivery_lease on duty_delivery (lease_until)
    where state = 'CLAIMED';
-- 降级扫描：DEAD 行 → watcher 条件推进次优先级
create index ix_duty_delivery_dead on duty_delivery (updated_at)
    where state = 'DEAD';

-- ---------- 9. 授权（V9 同构：读写角色分离，列级 UPDATE 只开所需） ----------

-- control_app：排班管理 CRUD（member/channel/schedule 软删面=active/enabled；
-- layer/layer_member/override 物理删——排班编辑语义）
grant select, insert, update on duty_member to control_app;
grant select, insert, update on duty_channel to control_app;
grant select, insert, update on duty_schedule to control_app;
grant select, insert, update, delete on duty_layer to control_app;
grant select, insert, delete on duty_layer_member to control_app;
grant select, insert, delete on duty_override to control_app;
-- control_app：台账只增（无 DELETE，不变量 3）+ 已读 CAS 两列（契约④）
grant select, insert on duty_notification to control_app;
grant update (status, read_at) on duty_notification to control_app;
-- control_app：派发首投行 + watcher 降级补投行铸造与条件推进
grant select, insert, update on duty_delivery to control_app;

-- notify_app：只碰投递面（领取/退避/终态）；无 INSERT——补投行只由 control_app
-- watcher 铸造（降级链单写者），无 DELETE——台账行终态只经 UPDATE
grant select on duty_notification to notify_app;
grant select on duty_channel to notify_app;
grant select on duty_delivery to notify_app;
grant update (
    state, lease_owner, lease_until, lease_epoch,
    attempt_count, available_at, last_error, sent_at, updated_at
) on duty_delivery to notify_app;

-- 显式冻结（V2/V9 惯例）：PUBLIC 零权限
revoke all on duty_member, duty_channel, duty_schedule, duty_layer, duty_layer_member, duty_override, duty_notification, duty_delivery from public;
revoke all on duty_member, duty_channel, duty_schedule, duty_layer, duty_layer_member, duty_override, duty_notification, duty_delivery from publisher_app;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on duty_member, duty_channel, duty_schedule, duty_layer, duty_layer_member, duty_override, duty_notification, duty_delivery from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on duty_member, duty_channel, duty_schedule, duty_layer, duty_layer_member, duty_override, duty_notification, duty_delivery from chaos_admin_app;
    end if;
end
$$;
