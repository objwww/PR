-- ============================================================================
-- V136 —— 3.15 复盘补强：整改项清单（Rootly/incident.io Postmortem 的 action items 语义）
-- 人工登记真数据（系统不代拟）；复盘闭环为派生态——整改项全部 DONE 即"复盘已闭环"，
-- 不另设闭列表列，避免双真源。表级授权与建表同批授予（V134 教训：写面授权必须随迁移）。
-- ============================================================================

create table action_items (
    id          uuid primary key,
    incident_id uuid not null references incident (id),
    title       text not null,
    owner       text,
    state       text not null default 'OPEN' check (state in ('OPEN', 'DONE')),
    created_by  text not null,
    created_at  timestamptz not null default now(),
    done_at     timestamptz
);

create index idx_action_items_incident on action_items (incident_id);

grant select, insert, update on action_items to control_app;
