-- ============================================================================
-- V42 —— EX-C3a 认证事件结构化落表
--   append-only 审计事实：LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT（首个版本即
--   此三类；会话过期由容器会话失效面产生 HTTP 401，不伪造"过期事件"行）。
--   单人运维授权形态（env 预置单账号）如实记载于 SecurityConfig，不在表内伪造
--   多用户字段。行只增不改（与 change_event 同律）。
--
-- 回滚（一语句）：
--   drop table auth_event;
-- ============================================================================

create table auth_event (
    id            bigserial primary key,
    occurred_at   timestamptz not null default now(),
    actor         text not null,          -- 认证主体（登录失败时为提交的用户名原文）
    event_type    text not null check (event_type in ('LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT')),
    remote_addr   text,                   -- X-Forwarded-For 首值或 request remoteAddr（nginx 单层）
    detail        text                    -- 失败原因类目等（不含密码，不含会话 id）
);

create index idx_auth_event_actor_time on auth_event (actor, occurred_at);

comment on table auth_event is
    'EX-C3a 认证事件（append-only）：登录成功/失败/注销三类；remote_addr 为单层代理面地址，detail 只存原因类目不存凭证';

grant select, insert on auth_event to control_app;
grant select, usage on sequence auth_event_id_seq to control_app;
