-- ============================================================================
-- V133 —— 3.11 评测中心增强（docs/告警-前端产品化技术方案-v1.md §3.11）：治理打标两件
--
-- 1) eval_run 治理标签（废批治理：有效/废批-超时/废批-其他/归档；NULL=未打标）
--    打标人/打标时间随行落档，审计可追溯；表级既有 grant 及于新列（V82 同律）。
-- 2) eval_dataset_tier 数据集分层标签（冒烟/回归/探索/红队——评测分层纪律）；
--    数据集注册面为只读 registry（name+version 定位），分层元数据独立成表避免
--    改注册结构；主键 (name, version) 幂等 upsert。
-- ============================================================================

alter table eval_run
    add column governance_tag         text,
    add column governance_tagged_by   text,
    add column governance_tagged_at   timestamptz;

alter table eval_run
    add constraint ck_eval_run_governance_tag
        check (governance_tag is null or governance_tag in
            ('VALID','SCRAP_TIMEOUT','SCRAP_OTHER','ARCHIVED'));

create table eval_dataset_tier (
    dataset_name    text not null,
    dataset_version text not null,
    tier            text not null,
    tagged_by       text not null,
    tagged_at       timestamptz not null default now(),
    primary key (dataset_name, dataset_version),
    constraint ck_eval_dataset_tier
        check (tier in ('SMOKE','REGRESSION','EXPLORE','REDTEAM'))
);

grant select, insert, update on eval_dataset_tier to control_app;
