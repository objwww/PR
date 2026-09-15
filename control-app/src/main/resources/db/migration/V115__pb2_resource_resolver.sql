-- PB-B2（Phase B 第二片）：Resource Resolver 权威解析 + Scope Snapshot + 授权扩张。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §2.2（R6）：
--   * 信任根论证：HMAC 只证明"这个 source 发过这条数据"，不证明 env/resource 标签
--     是正确的授权信息——授权不依赖不可信业务 payload；alert.resource_key 仅是
--     <b>requested</b> resource，canonical 身份只来自本清单（B 组不变量：告警标签
--     零授权效力，Resolver miss = fail-closed 不可授权）；
--   * Scope Authorization 判定结果落 scope_snapshot（canonical JSON + sha256 锚），
--     审批批的是 resolved snapshot，执行前同事务复核（复核随 B4 消费模板）；
--   * 授权扩张 authorized_scope_expansion：扩张不是 Agent 自行扩大——每条扩张必须
--     新 Policy 判定 + 新审批锚定（approval_id，Phase C 回填；锚位缺失 = 永不
--     可进入计划/执行面）。
--
-- 种子数据说明：demo 前缀三条为 195 演示窗 fake-write 链演练资源（canonical_env=
-- demo 显式可见），生产清单由运维面维护，本迁移不做任何生产身份声明。

-- ---------- 1. resource_inventory：权威资源清单（canonical identity 唯一来源） ----------

create table resource_inventory (
    resource_uid    text primary key,       -- canonical 身份（§2.2 resolver 输出面）
    canonical_env   text not null,
    canonical_team  text not null,
    resource_kind   text not null,
    labels          jsonb not null default '{}'::jsonb,
    resource_version bigint not null default 0,   -- 漂移检测锚（审批期漂移=消费拒绝，§2.7）
    enabled         boolean not null default true, -- 下线资源不可解析（fail-closed）
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- 请求键 → canonical 身份（多别名同源；键不是身份——身份只在 inventory）
create table resource_alias (
    resource_key    text primary key,
    resource_uid    text not null references resource_inventory(resource_uid),
    created_at      timestamptz not null default now()
);

comment on table resource_inventory is
    'PB-B2：权威资源清单（§2.2 R6）——授权资源的 canonical_env/team/version 只来自'
    '本表；告警标签/业务 payload 零授权效力（B 组不变量）；enabled=false 即不可解析';

-- ---------- 2. action_intent 携带解析结果（快照 + 锚） ----------

alter table action_intent
    add column resolved_resource_uid text,
    add column scope_snapshot        jsonb,
    add column scope_snapshot_hash   text,
    add column resolved_at           timestamptz;

comment on column action_intent.scope_snapshot_hash is
    'PB-B2：scope snapshot 的 sha256 锚——审批批快照、执行前同事务复核该锚（B4）';

-- ---------- 3. scope_expansion：授权扩张台账（每条必须独立审批锚定） ----------

create table scope_expansion (
    expansion_id            uuid primary key,
    run_id                  uuid not null,
    requested_resource_key  text not null,
    resolved_resource_uid   text,           -- 解析成功即填；miss 的提案不存在（fail-closed）
    reason                  text not null,
    scope_snapshot          jsonb not null,
    scope_snapshot_hash     text not null,
    status                  varchar(24) not null default 'PENDING_APPROVAL',
    approval_id             text,           -- Phase C 审批锚（三层对象 Request/Grant 之上）
    created_at              timestamptz not null default now(),
    decided_at              timestamptz,

    constraint ck_scope_expansion_status
        check (status in ('PENDING_APPROVAL','APPROVED','REJECTED')),
    constraint ck_scope_expansion_anchor
        check ((status = 'APPROVED') = (approval_id is not null))
);

create index ix_scope_expansion_run on scope_expansion(run_id, created_at);
create index ix_scope_expansion_pending on scope_expansion(status) where status = 'PENDING_APPROVAL';

comment on table scope_expansion is
    'PB-B2：授权扩张台账（§2.2）——initial_scope 外的资源进入授权面必须逐条'
    'PENDING_APPROVAL→(Phase C 审批锚)→APPROVED；无锚 APPROVED 被 DDL 拒绝'
    '（B 组不变量：scope 扩张必有新审批锚定）';

-- ---------- 4. 演示窗种子（fake-write 链演练资源；canonical_env=demo 显式非生产） ----------

insert into resource_inventory (resource_uid, canonical_env, canonical_team, resource_kind, labels)
values
    ('res://demo/checkout',   'demo', 'payments', 'service',  '{"stateless":"true"}'),
    ('res://demo/frontend',   'demo', 'web',      'service',  '{"stateless":"true"}'),
    ('res://demo/orders-db',  'demo', 'orders',   'database', '{"stateless":"false"}')
on conflict (resource_uid) do nothing;

insert into resource_alias (resource_key, resource_uid)
values
    ('checkout',    'res://demo/checkout'),
    ('checkout-api','res://demo/checkout'),
    ('frontend',    'res://demo/frontend'),
    ('orders-db',   'res://demo/orders-db')
on conflict (resource_key) do nothing;

-- ---------- 5. 授权 ----------

grant select, insert, update on resource_inventory to control_app;
grant select, insert, update on resource_alias to control_app;
grant select, insert, update on scope_expansion to control_app;
