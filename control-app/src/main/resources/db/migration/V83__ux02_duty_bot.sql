-- ============================================================================
-- V83 —— UX-02 值班仿真机器人会话模型
--   （docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md §六/§七 UX-02 卡；
--     docs/告警-UX迭代-分类与值班机器人方案-v1.md §二）
--
-- 归属裁定：仿真机器人后端归 control-app 认证域（/api/v1/duty-bot/**，
--   ROLE_OPERATOR 沿 /api/v1/** 矩阵）；duty-adapter 是独立外部探针故障域，
--   不为聊天引入数据库依赖——本迁移零 duty-adapter 触点。
--
-- 两表：
--   chat_session  仿真会话（id/标题/创建人 owner/创建时间）——owner = 认证主体
--                 （AuthenticatedActor，不采信自报）；会话级隔离由应用面裁决
--                 （越权访问 404，不泄露存在性），DB 面无跨 owner 开口。
--   chat_message  消息（insert-only）：role ∈ {user, assistant}；assistant 行带
--                 识别意图 intent 与引用实体快照 references_json（[{type,id}]，
--                 incidentId 等，供前端跳转）；用户行可带 client_message_id
--                 幂等锚（同会话同键重放不重复落，部分唯一索引兜底）。
--   seq（identity）= 单调插入序：分页游标 + 幂等重放时"用户消息之后的首条
--   assistant 回复"配对锚（同事务两条 created_at 相同，随机 uuid 不保序）。
--
-- insert-only 纪律：两表只授 SELECT+INSERT——会话无改名/删除面，消息无编辑面
--   （仿真留痕即审计；要新内容就发新消息）。
--
-- 授权（V9/V43/V82 同构）：control_app 只增读；publisher/notify/eval/PUBLIC
--   显式归零（eval_app 不写告警/运维域，防未来 grant all 漂移）。
--
-- 回滚：drop table chat_message, chat_session;
-- ============================================================================

-- ---------- 1. chat_session：仿真会话 ----------

create table chat_session (
    id         uuid primary key,
    title      text not null,
    owner      text not null,
    created_at timestamptz not null,

    constraint ck_chat_session_title
        check (length(btrim(title)) > 0 and length(title) <= 80),
    constraint ck_chat_session_owner
        check (length(btrim(owner)) > 0 and length(owner) <= 64)
);

-- 会话列表 = 本人会话按创建时间倒序（键集游标 (created_at, id)）
create index ix_chat_session_owner on chat_session (owner, created_at desc, id desc);

-- ---------- 2. chat_message：消息（insert-only） ----------

create table chat_message (
    id                uuid primary key,
    seq               bigint generated always as identity,   -- 单调插入序（游标/配对锚）
    session_id        uuid not null references chat_session(id),
    role              varchar(10) not null,
    content           text not null,
    intent            text,                -- assistant 行：DUTY_ONCALL/INCIDENT_QUERY/…
    references_json   jsonb,               -- 引用实体快照 [{type,id}]；无引用 = null
    client_message_id text,                -- 用户行幂等锚（前端重发去重），可空
    created_at        timestamptz not null,

    constraint ck_chat_message_role check (role in ('user', 'assistant')),
    -- 用户消息上限 400（应用面）；库面给 assistant 回复留余量，两角色共用 2000 硬顶
    constraint ck_chat_message_content
        check (length(btrim(content)) > 0 and length(content) <= 2000),
    -- intent 与 assistant 同生同灭（user 行恒 null）
    constraint ck_chat_message_intent
        check ((role = 'assistant') = (intent is not null)),
    -- 幂等锚只属于用户行
    constraint ck_chat_message_client_id
        check (client_message_id is null or role = 'user')
);

-- 幂等锚：同会话同 client_message_id 至多一行（空键不参与）
create unique index uq_chat_message_client on chat_message (session_id, client_message_id)
    where client_message_id is not null;

-- 会话内消息流：seq 降序翻页（最新在前，向上翻历史）
create index ix_chat_message_session on chat_message (session_id, seq desc);

-- ---------- 3. 授权（V9/V43/V82 同构：只增读 + 显式冻结） ----------

-- control_app：仿真面读写（零 UPDATE/DELETE 开口 = insert-only）
grant select, insert on chat_session to control_app;
grant select, insert on chat_message to control_app;

-- 显式冻结：publisher/notify/eval/PUBLIC 归零；identity 序列权限随表 grant 不开放
-- （identity 列插入无需序列授权——generated always as identity 由表 owner 面托管，
--   INSERT 走 DEFAULT 即可，control_app 不显式引用序列）。
revoke all on chat_session, chat_message from publisher_app, notify_app, eval_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on chat_session, chat_message from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on chat_session, chat_message from chaos_admin_app;
    end if;
end
$$;
