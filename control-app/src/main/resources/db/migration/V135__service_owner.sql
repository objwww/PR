-- ============================================================================
-- V135 —— 3.13 服务目录补强：服务负责人登记（Backstage Catalog 的 owner 语义）
-- 负责人属人工配置真数据（谁对哪个服务负责），非指标；主键=服务名幂等 upsert。
-- ============================================================================

create table service_owner (
    service_name text primary key,
    owner        text not null,
    note         text,
    tagged_by    text not null,
    tagged_at    timestamptz not null default now()
);

grant select, insert, update on service_owner to control_app;
