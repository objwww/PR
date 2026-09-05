-- ============================================================================
-- V1 —— AM2 arena 域：schema + 角色 DO 块 + 订单基础表（M2-03/M2-04）
--   设计依据：docs/告警AM2-落码技术方案.md v2.0（C-1/C-2/C-3 冻结裁定）+ AM2 v3.0 §6.1/6.2
--   本迁移由 owner（postgres）经 deploy/alert compose 的 arena-migrate one-shot 执行；
--   历史表落 arena.flyway_schema_history（FLYWAY_SCHEMAS=arena），与 control 域（public）互不干扰。
--   角色 DO 块与 deploy/db/01-roles.sh 语义一致——存量库（无 initdb 重跑机会）由此补齐；
--   密码经 Flyway placeholder 注入（arena_password/chaos_admin_password/eval_password），
--   不落任何 yml/文件。
--
-- 权限矩阵（C-3 冻结；INV-AM2-3/INV-AM2-6 的落地面）：
--   arena_app        arena 业务表 RW；chaos 域零权限（V3 起另授 session SELECT + event INSERT）
--   chaos_admin_app  业务表零权限（session/event/GT 写者，见 V3）
--   eval_app         业务表 SELECT（评测读事实）；GT 权限在 V3
--   control_app / publisher_app / PUBLIC  零权限（显式 revoke 防漂移）
-- ============================================================================

create schema if not exists arena;

-- ---------- 角色（幂等；与 01-roles.sh 等价，密码走 placeholder） ----------
do $$
begin
    if not exists (select from pg_roles where rolname = 'arena_app') then
        create role arena_app login password '${arena_password}';
    else
        alter role arena_app with login password '${arena_password}';
    end if;
    if not exists (select from pg_roles where rolname = 'chaos_admin_app') then
        create role chaos_admin_app login password '${chaos_admin_password}';
    else
        alter role chaos_admin_app with login password '${chaos_admin_password}';
    end if;
    if not exists (select from pg_roles where rolname = 'eval_app') then
        create role eval_app login password '${eval_password}';
    else
        alter role eval_app with login password '${eval_password}';
    end if;
end
$$;

-- ---------- 1. oa_trade_order 交易单（三单之首） ----------
-- booking_status：CREATED(不可见)→ENABLED(生效)→DISCARDED(废单终态)；迁移合法性由
-- BookingStateMachine 把关（DB 只守值域）；CREATED 不可见 = 查询 API 过滤（M2-09）。
-- 注意：intent_id 无唯一约束——F1 幂等失效注入的重复单必须"能被制造出来"，
-- 重复检测是 DomainProbe 的职责（探测发现，INV-AM2-5），不是 DB 约束。
create table arena.oa_trade_order (
    id              uuid primary key,
    intent_id       text not null,
    correlation_id  text not null,            -- live-/chaos- 前缀（INV-AM2-1 的判定面）
    buyer_id        text not null,
    sku             text not null,
    quantity        integer not null check (quantity > 0),
    amount          numeric(12,2) not null check (amount >= 0),
    booking_status  varchar(16) not null default 'CREATED',
    pay_status      varchar(16) not null default 'NOT_PAY',
    discard_reason  text,
    created_at      timestamptz not null default now(),
    enabled_at      timestamptz,
    updated_at      timestamptz not null default now(),

    constraint ck_trade_booking check (booking_status in ('CREATED','ENABLED','DISCARDED')),
    constraint ck_trade_pay check (pay_status in ('NOT_PAY','PAID','REFUNDED')),
    constraint ck_trade_discard_reason check (
        (booking_status = 'DISCARDED') = (discard_reason is not null))
);

create index ix_trade_intent on arena.oa_trade_order(intent_id);
-- 卡单扫描面（DomainProbe stuck 判定：CREATED 停留超阈值）
create index ix_trade_created on arena.oa_trade_order(created_at)
    where booking_status = 'CREATED';

-- ---------- 2. oa_fulfillment_order 履约单（与交易单同事务成对出生，C-1） ----------
create table arena.oa_fulfillment_order (
    id              uuid primary key,
    trade_order_id  uuid not null references arena.oa_trade_order(id),
    state           varchar(16) not null default 'CONFIRMING',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint ck_fulfillment_state
        check (state in ('CONFIRMING','CONFIRMED','NO_ROOM','CANCELLED')),
    constraint uq_fulfillment_order unique (trade_order_id)
);

-- ---------- 3. oa_payment_record 支付事实流水（C-1：支付不是业务单，但必须有事实） ----------
-- F2 的"PAID 但无支付事实"由 DomainProbe 依本表检出；PayStateMachine 把守 result 迁移
-- （UNKNOWN→RECONCILING→SUCCEEDED/DECLINED 为 F3 对账路径，迟到成功 = UNKNOWN→SUCCEEDED）。
create table arena.oa_payment_record (
    id           uuid primary key,
    order_id     uuid not null references arena.oa_trade_order(id),
    attempt_no   integer not null check (attempt_no >= 1),
    kind         varchar(8) not null,         -- AUTH=创单授权（创单第二步）；CAPTURE=pay() 回调
    result       varchar(16) not null,
    amount       numeric(12,2) not null check (amount >= 0),
    initiated_at timestamptz not null default now(),
    settled_at   timestamptz,

    constraint ck_payment_kind check (kind in ('AUTH','CAPTURE')),
    constraint ck_payment_result
        check (result in ('INITIATED','SUCCEEDED','DECLINED','UNKNOWN','RECONCILING')),
    constraint ck_payment_settled check (
        (result in ('SUCCEEDED','DECLINED')) = (settled_at is not null)),
    constraint uq_payment_attempt unique (order_id, attempt_no)
);

create index ix_payment_order on arena.oa_payment_record(order_id, initiated_at);

-- ---------- 4. oa_resource_ledger 资源台账（逐资源扣减/回补流水） ----------
-- 回补幂等锚：REFUND 行对 (order_id, resource_type, deduction_seq) 唯一——
-- 补偿 worker 重投时"台账已有 REFUND 记录即跳过"（AM2 v3.0 §6.3）。
create table arena.oa_resource_ledger (
    id             uuid primary key,
    order_id       uuid not null references arena.oa_trade_order(id),
    resource_type  varchar(16) not null,
    direction      varchar(8) not null,
    deduction_seq  integer not null check (deduction_seq >= 1),
    quantity       integer not null check (quantity > 0),
    created_at     timestamptz not null default now(),

    constraint ck_ledger_type
        check (resource_type in ('INVENTORY','DISCOUNT','PURCHASE_LIMIT','ASSET')),
    constraint ck_ledger_direction check (direction in ('DEDUCT','REFUND')),
    constraint uq_ledger_entry unique (order_id, resource_type, deduction_seq, direction)
);

create index ix_ledger_order on arena.oa_resource_ledger(order_id, deduction_seq);

-- ---------- 5. oa_refund_order 退款单（M2-11 三单一致性） ----------
create table arena.oa_refund_order (
    id                 uuid primary key,
    trade_order_id     uuid not null references arena.oa_trade_order(id),
    reason             text not null,
    responsible_party  varchar(8) not null,
    amount             numeric(12,2) not null check (amount > 0),
    state              varchar(16) not null default 'REQUESTED',
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    settled_at         timestamptz,

    constraint ck_refund_party check (responsible_party in ('BUYER','SUPPLIER')),
    constraint ck_refund_state
        check (state in ('REQUESTED','APPROVED','REFUNDING','SUCCEEDED','REJECTED','FAILED','CANCELLED')),
    constraint ck_refund_settled check (
        (state in ('SUCCEEDED','REJECTED','CANCELLED')) = (settled_at is not null))
);

create index ix_refund_order on arena.oa_refund_order(trade_order_id);

-- ---------- 6. oa_idempotency_record 幂等记录（C-2 全字段，F1 注入对象） ----------
-- 语义冻结：同 key 同 digest 且 CONSUMED → 重放原结果；同 key 同 digest 且 PROCESSING
-- → 202 处理中；同 key 不同 digest → 409；租约过期回收 PROCESSING（claim 时 epoch+1）。
create table arena.oa_idempotency_record (
    intent_id        text primary key,
    request_digest   char(64) not null,
    state            varchar(16) not null default 'NEW',
    owner            text,
    lease_until      timestamptz,
    lease_epoch      bigint not null default 0 check (lease_epoch >= 0),
    result_order_id  uuid,
    response_digest  char(64),
    expires_at       timestamptz not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),

    constraint ck_idem_state check (state in ('NEW','PROCESSING','CONSUMED','EXPIRED')),
    constraint ck_idem_consumed check (
        (state = 'CONSUMED') = (result_order_id is not null and response_digest is not null)),
    constraint ck_idem_processing check (
        state <> 'PROCESSING' or (owner is not null and lease_until is not null))
);

-- 崩溃回收扫描面：租约过期的 PROCESSING 可被重领
create index ix_idem_lease on arena.oa_idempotency_record(lease_until)
    where state = 'PROCESSING';

-- ---------- 7. 授权（C-3 矩阵的业务表面） ----------
grant usage on schema arena to arena_app, chaos_admin_app, eval_app;

grant select, insert, update on arena.oa_trade_order,
    arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order,
    arena.oa_idempotency_record
    to arena_app;

grant select on arena.oa_trade_order,
    arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order,
    arena.oa_idempotency_record
    to eval_app;

-- 防漂移显式冻结：其余既有角色与 PUBLIC 在 arena 业务表零权限
revoke all on arena.oa_trade_order, arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order, arena.oa_idempotency_record
    from control_app;
revoke all on arena.oa_trade_order, arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order, arena.oa_idempotency_record
    from publisher_app;
revoke all on arena.oa_trade_order, arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order, arena.oa_idempotency_record
    from chaos_admin_app;
revoke all on arena.oa_trade_order, arena.oa_fulfillment_order, arena.oa_payment_record,
    arena.oa_resource_ledger, arena.oa_refund_order, arena.oa_idempotency_record
    from public;

-- default privileges 覆盖新表（安全方向）：未来 arena schema 的新表自动对 eval_app 可读
-- （评测侧读事实面不断链）；arena_app/chaos_admin_app 的写授权必须逐迁移显式授——
-- 漏授即应用报错可见，而不是静默泄漏。
alter default privileges in schema arena
    grant select on tables to eval_app;
