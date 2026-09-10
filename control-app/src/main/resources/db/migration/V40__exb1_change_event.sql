-- ============================================================================
-- V40 —— EX-B1 change_event 真实变更源（与生效同事务）
--   append-only 变更事实表：config 激活/回滚（control_app 与 pointer CAS 同事务
--   写入）+ 部署脚本事实（deploy_app 写角色，SET ROLE 写入）。
--   采集完整性不在表本身，在同事务：CAS 败者与幂等重放零事件（评审 B1）。
--
-- 回滚（两语句）：
--   drop table change_event;
--   drop role deploy_app;
-- ============================================================================

-- ---------- 1. change_event：append-only 变更事实 ----------

create table change_event (
    id            uuid primary key,
    deploy_id     text not null,
    source        text not null check (source in ('config_activation', 'deployment')),
    action        text not null check (action in ('ACTIVATE', 'ROLLBACK', 'DEPLOY')),
    service       text not null,
    environment   text not null,
    image_digest  text,
    config_digest char(64),
    commit_sha    text,
    actor         text not null,
    started_at    timestamptz,
    effective_at  timestamptz not null,
    rollback_of   char(64),
    status        text not null check (status in ('SUCCEEDED', 'FAILED')),
    created_at    timestamptz not null default now(),
    -- 幂等锚：脚本重试/重放 on conflict do nothing，不产生第二个"生效"事件
    constraint uq_change_event_source_deploy unique (source, deploy_id)
);

create index idx_change_event_service_window on change_event (service, effective_at);

comment on table change_event is
    'EX-B1 变更事实（append-only）：config_activation 行与 config_bundle_active pointer CAS 同事务落库；deployment 行由部署脚本经 deploy_app 写入；CAS 败者与幂等重放零事件';
comment on column change_event.deploy_id is
    '部署事实身份：deployment=脚本生成 am4-<UTC时间戳>；config_activation=每次成功 CAS 一枚 UUID';
comment on column change_event.rollback_of is
    '仅 ROLLBACK 行：回滚前生效的 config digest（DEPLOY/ACTIVATE 行为 NULL）';
comment on column change_event.commit_sha is
    'v1.0 列名 commit 为 SQL 关键字，落 commit_sha（契约文档 §2 偏离声明）';

-- ---------- 2. deploy_app：部署面写角色（与 control_app 读/激活面分离） ----------

do $$
begin
    if not exists (select from pg_roles where rolname = 'deploy_app') then
        create role deploy_app nologin;
    end if;
end
$$;

grant usage on schema public to deploy_app;
grant insert on change_event to deploy_app;

-- ---------- 3. 授权（V24 惯例：append-only 面 + 显式冻结） ----------

-- control_app：激活事实同事务 insert + change.query 读；无 update/delete（终态不可改）
grant select, insert on change_event to control_app;
revoke update, delete on change_event from control_app;

revoke all on change_event
    from publisher_app, notify_app, eval_app, public;
