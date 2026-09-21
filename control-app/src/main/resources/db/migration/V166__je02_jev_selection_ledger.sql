-- JE-02（Jev 选材审计面）：每次 Jev 选材决策一行——池清单/保护项/选中项/被裁项/
-- 逐候选概率/是否应用（SELECT 才改输入）/关联账本行。回答"Jev 到底挑了什么、
-- 裁了什么"——此前只有 JEV_SELECTION 日志计数（不落库、无 ref 清单），审计断层。
--
-- 回滚语义：drop table（纯新增，存量行为零依赖）。

create table rca_jev_selection (
    id uuid primary key,
    run_id uuid not null,
    task_id uuid not null,
    mode varchar(16) not null,
    applied boolean not null,
    pool_refs jsonb not null,
    protected_refs jsonb not null,
    selected_refs jsonb not null,
    omitted_refs jsonb not null,
    probabilities jsonb,
    model_call_id uuid,
    policy_digest character(64),
    latency_ms bigint,
    created_at timestamptz not null default now()
);

create index ix_rca_jev_selection_run on rca_jev_selection (run_id, created_at);

do $$ begin
    if exists (select from pg_roles where rolname='control_app') then
        grant select,insert on rca_jev_selection to control_app;
    end if;
    if exists (select from pg_roles where rolname='eval_app') then
        grant select on rca_jev_selection to eval_app;
    end if;
end $$;
revoke all on rca_jev_selection from public;
