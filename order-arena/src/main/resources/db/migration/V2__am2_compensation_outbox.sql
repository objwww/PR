-- ============================================================================
-- V2 —— AM2 arena 域：补偿 outbox（M2-05）
--   设计依据：AM2 v3.0 §6.3（PG outbox 驱动废单补偿，不引入 MQ）+ C-4（修复动作幂等、
--   失败可重试、受 lease epoch 栅栏）。
--   八态生命周期：
--     PENDING    已生产待领取（与业务废单同事务写入，M2-12）
--     CLAIMED    worker 领取（持租约）
--     EXECUTING  反向回补执行中（多资源回补中途崩溃的可见中间态）
--     SUCCEEDED  回补完成终态（台账 REFUND 行齐全）
--     SKIPPED    幂等跳过终态（台账已有 REFUND 记录 = 他人已补）
--     RETRY_WAIT 失败退避，到期可重领
--     DEAD       重试耗尽终态（毒事件；需人工介入，DP-C04 断言其不存在）
--     CANCELLED  取消终态（订单恢复路径改判时）
--   一致性约束：终态必有 finished_at；EXECUTING/CLAIMED 必有租约三件套。
-- ============================================================================

create table arena.oa_compensation_outbox (
    id            uuid primary key,
    order_id      uuid not null references arena.oa_trade_order(id),
    event_type    varchar(32) not null default 'RESOURCE_REFUND',
    payload       jsonb not null,             -- 反向补偿计划：[{resourceType, deductionSeq, quantity}]
    state         varchar(16) not null default 'PENDING',

    lease_owner   text,
    lease_until   timestamptz,
    lease_epoch   bigint not null default 0 check (lease_epoch >= 0),

    attempt_count integer not null default 0,
    max_attempts  integer not null default 5,
    available_at  timestamptz not null default now(),
    last_error    jsonb,

    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    finished_at   timestamptz,

    constraint ck_outbox_state
        check (state in ('PENDING','CLAIMED','EXECUTING','SUCCEEDED',
                         'SKIPPED','RETRY_WAIT','DEAD','CANCELLED')),
    constraint ck_outbox_event_type
        check (event_type in ('RESOURCE_REFUND')),
    constraint ck_outbox_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
    constraint ck_outbox_terminal_finished
        check (state not in ('SUCCEEDED','SKIPPED','DEAD','CANCELLED') or finished_at is not null),
    constraint ck_outbox_lease_present
        check (state not in ('CLAIMED','EXECUTING')
               or (lease_owner is not null and lease_until is not null)),
    constraint ck_outbox_terminal_lease_absent
        check (state not in ('SUCCEEDED','SKIPPED','DEAD','CANCELLED') or lease_owner is null)
);

-- 领取排序：可用时刻 FIFO；部分索引 = claim 窄面（EXPLAIN 断言命中本索引，M2-05 验收）
create index ix_outbox_claim on arena.oa_compensation_outbox(available_at, created_at)
    where state in ('PENDING','RETRY_WAIT');

-- 崩溃回收：租约过期的在途行可被重领
create index ix_outbox_lease on arena.oa_compensation_outbox(lease_until)
    where state in ('CLAIMED','EXECUTING');

-- 每订单至多一条未完结补偿（同订单重复废单事件不叠加堆积）
create unique index uq_outbox_active_order on arena.oa_compensation_outbox(order_id)
    where state in ('PENDING','CLAIMED','EXECUTING','RETRY_WAIT');

-- ---------- 授权（补偿域属业务执行面） ----------
grant select, insert, update on arena.oa_compensation_outbox to arena_app;
grant select on arena.oa_compensation_outbox to eval_app;

revoke all on arena.oa_compensation_outbox from control_app;
revoke all on arena.oa_compensation_outbox from publisher_app;
revoke all on arena.oa_compensation_outbox from chaos_admin_app;
revoke all on arena.oa_compensation_outbox from public;
