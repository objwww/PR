-- PB-B1（Phase B 第一片）：Mutation Safety Plane 账本层——action_intent 意图台账
-- + rca_operation 操作台账（状态机域）。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md
--   §1 R2/R3 目标执行链（Agent → ActionIntent → … → Durable Operation → Outbox）
--   §2.1 ActionDigest = 授权身份锚（intent/operation 全链绑定同一 digest）
--   §2.11 Operation 状态机（mutation 域；UNKNOWN=中间态必须被显式穿越）
--   §5 Phase B（"假写入"安全链：真实资源零 mutation）
--
-- 语义边界（Phase B 铁律）：
--   * 本迁移只落账本，不产生任何执行能力——R2/R3 仍 VALIDATE_ONLY（A10）；
--   * rca_operation 行只能由后续波次（B4 消费模板）在 dry_run=true 下铸出，
--     Phase D 解锁日退役 dry_run 检查（A10 同族，DDL 层先钉死）；
--   * resource_uid 可空 = Resource Resolver（B2）权威解析前不得定位资源，
--     告警标签零授权效力（B 组不变量）；
--   * 授权事实只来自 rca_event 读路径（B 组不变量）——账本行是投影/裁决输入，
--     审计叙事以事件账本为准。

-- ---------- 1. action_intent：一次 R2/R3 调用意图的持久账本行 ----------

create table action_intent (
    intent_id       uuid primary key,
    run_id          uuid not null,
    task_id         uuid,
    attempt_id      uuid,
    call_seq        bigint not null,
    tool_name       text not null,
    tool_version    text not null,
    action_id       text not null,          -- = tool_name（§2.1 action_id 语义，显式列随链走）
    action_digest   text not null,          -- sha256(canonical envelope)，授权身份锚（§2.1）
    risk            varchar(8) not null,    -- R2 / R3（ToolRisk.name）
    requested_resource_key text,            -- 请求面资源键（B2 Resolver 权威解析的输入，非授权依据）
    status          varchar(16) not null default 'OPEN',
    operation_id    uuid,                   -- PLANNED 后回填（B4 消费模板）
    args_json       jsonb not null,         -- canonical args（与 digest 同源规范化）
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint ck_action_intent_status
        check (status in ('OPEN','PLANNED','VOIDED')),
    constraint ck_action_intent_risk
        check (risk in ('R2','R3')),
    constraint ck_action_intent_lifecycle
        check ((status = 'PLANNED') = (operation_id is not null))
);

create index ix_action_intent_run on action_intent(run_id, created_at);
create index ix_action_intent_digest on action_intent(action_digest);
create index ix_action_intent_status on action_intent(status) where status = 'OPEN';

comment on table action_intent is
    'PB-B1：R2/R3 调用意图台账（§1 执行链入口账本）——ToolGateway VALIDATE_ONLY '
    '路径意图事件同短事务入账；Phase C 审批 Request/Grant 由此派生；'
    'Phase D 解锁前本表零执行效力（A10）';

-- ---------- 2. rca_operation：mutation 域操作台账（状态机见域层） ----------

create table rca_operation (
    operation_id    uuid primary key,
    intent_id       uuid not null references action_intent(intent_id),
    run_id          uuid not null,
    task_id         uuid,
    action_id       text not null,
    action_digest   text not null,
    resource_uid    text,                   -- B2 Resolver 权威身份；未解析不得定位资源
    resource_epoch  bigint not null default 0,  -- §2.9：资源上第几波 mutation（与 run lease_epoch 无关）
    status          varchar(28) not null,
    dry_run         boolean not null default true,
    params_json     jsonb not null,         -- canonical args 快照（§2.1 digest 同源）
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    prepared_at     timestamptz,
    dispatched_at   timestamptz,
    ack_at          timestamptz,
    verified_at     timestamptz,
    completed_at    timestamptz,
    last_error      jsonb,

    constraint ck_rca_operation_status
        check (status in ('PREPARED','DISPATCHED','ACKNOWLEDGED','VERIFIED','COMPLETED',
                          'UNKNOWN','RECONCILING','RETRYABLE','ESCALATED',
                          'FAILED_CONFIRMED','CANCELLED_BEFORE_DISPATCH')),
    constraint ck_rca_operation_dry_run_phase check (dry_run),
    constraint ck_rca_operation_prepared check (
        (status <> 'PREPARED') = (prepared_at is not null))
);

create index ix_rca_operation_run on rca_operation(run_id, created_at);
create index ix_rca_operation_intent on rca_operation(intent_id);
create index ix_rca_operation_resource on rca_operation(resource_uid, created_at);
-- 非终态扫描面（reconcile/悬挂发现：§2.8 PREPARED 悬挂 ESCALATE、UNKNOWN→RECONCILING）
create index ix_rca_operation_active on rca_operation(status)
    where status in ('PREPARED','DISPATCHED','ACKNOWLEDGED','UNKNOWN','RECONCILING','RETRYABLE');

comment on table rca_operation is
    'PB-B1：mutation 域操作台账（§2.11 状态机）——B4 消费模板在同一 PG 事务完成 '
    'grant 验证/CAS/PREPARED/outbox/事件（§2.8）；UNKNOWN/RECONCILING 期间资源锁 '
    '不让渡（§2.9，B 组不变量）；Phase B 全量 dry_run=true（真实资源零 mutation）';

-- ---------- 3. 授权 ----------

grant select, insert, update on action_intent to control_app;
grant select, insert, update on rca_operation to control_app;
