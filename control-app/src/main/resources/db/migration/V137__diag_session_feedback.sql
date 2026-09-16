-- ============================================================================
-- V137 —— 3.4 补强：诊断回答评价（Datadog Bits/Ask AI 的 thumbs 语义）
-- 一答一评（session_id 主键，改评覆盖不堆历史）；踩可选填原因；
-- 表级授权与建表同批授予（V134 教训：改评 UPDATE 必须随迁移授）。
-- ============================================================================

create table diag_session_feedback (
    session_id  uuid primary key references diag_session (id),
    rating      text not null check (rating in ('UP', 'DOWN')),
    reason      text,
    created_by  text not null,
    created_at  timestamptz not null default now()
);

grant select, insert, update on diag_session_feedback to control_app;
