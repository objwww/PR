-- PD-D1（Phase D 第一片）：R2 极小范围解锁——A10 DDL 闸退役 + scoped mutation
-- invariants 接替 + 真实执行面（Runner 真派发）。
--
-- 设计基线 §5 Phase D：仅少数工具 × 少数 resource × 少数 env × 人工审批 mandatory
-- （例：restart 一个无状态 staging 服务）。Gate = Phase C 证据。
--
-- 解锁语义（scoped mutation invariants，接替 A10）：
--   * 注册表无行的 (tool, resource, env) = 永远 dry_run=true（默认封死）；
--   * 解锁行必须 tool+resource_uid+canonical_env 三元全匹配才真执行；
--   * 人工审批 mandatory：dry_run=false 铸造仍要求 approval.enabled=true 且 grant
--     已消费（消费模板前置不变）；
--   * enabled=false = 注册未撤销但停用（比删行可审计）。

-- 1. A10 DDL 闸退役（此日起由注册表三元匹配 + 审批前置接替）
alter table rca_operation drop constraint ck_rca_operation_dry_run_phase;
comment on column rca_operation.dry_run is
    'PD-D1：false = 注册表三元解锁 + 人工审批消费后的真实执行（scoped mutation）；'
    'true = dry-run。A10 DDL 全量闸退役，范围纪律移交 mutation_unlock_registry';

-- 2. 解锁注册表（少数工具 × 少数 resource × 少数 env 的显式白名单）
create table mutation_unlock_registry (
    unlock_id       uuid primary key,
    tool_name       text not null unique,   -- 每工具一行（最小暴露面）
    resource_uid    text not null,          -- 精确资源（不前缀不通配）
    canonical_env   text not null,
    enabled         boolean not null default true,
    unlocked_at     timestamptz not null default now(),
    note            text
);
comment on table mutation_unlock_registry is
    'PD-D1：scoped mutation 白名单——三元全匹配才允许 dry_run=false；'
    '人工审批 mandatory（消费模板前置，不因解锁而豁免）';

grant select, insert, update on mutation_unlock_registry to control_app;

-- 3. 演示窗解锁行（chaos.resolve @ res://demo/checkout @ demo——无状态演示资源）
insert into mutation_unlock_registry(unlock_id, tool_name, resource_uid, canonical_env, note)
values ('dddd0000-0000-0000-0000-000000000001', 'chaos.resolve', 'res://demo/checkout',
        'demo', 'Phase D 极小解锁：无状态 demo 资源的故障解除（人工审批 mandatory）');
