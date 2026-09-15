-- PB-B4（Phase B 第四片）：Transactional Operation Outbox + Dispatcher + dry-run Runner。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §2.8（R2）：
--   * 洞：consume 成功 → 事件落库 → 进程崩溃 → 外部操作从未 dispatch——approval=
--     consumed、operation 不存在，没有干净答案；
--   * 修：消费与派发指令同事务原子化——PREPARED 与 outbox 行同 COMMIT（B4 模板）；
--     COMMIT 后即使进程立即崩溃，outbox 还在，其他 Dispatcher 可继续；
--   * 派发 at-least-once：租约领取（SKIP LOCKED + lease_epoch）+ operation 幂等
--     （状态机当前态检查——已派发再投 = 幂等跳过不重执）；
--   * PREPARED/CLAIMED 悬挂由 reconcile 扫描发现（B5）——不静默丢、不自动重执。
--
-- Phase B 边界：Plan 模板的 grant 验证/CAS 两步随 Phase C 审批面插入（本波以
-- dry_run=true + DRY_RUN_SENTINEL 事件锚占位）；Runner dry-run only（A10）。

create table operation_outbox (
    outbox_id       uuid primary key,
    operation_id    uuid not null references rca_operation(operation_id),
    state           varchar(16) not null default 'PENDING',
    lease_owner     text,
    lease_until     timestamptz,
    lease_epoch     bigint not null default 0,
    attempt_count   integer not null default 0,
    max_attempts    integer not null default 5,
    available_at    timestamptz not null default now(),
    dispatched_at   timestamptz,
    last_error      jsonb,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint ck_operation_outbox_state
        check (state in ('PENDING','CLAIMED','DISPATCHED','FAILED')),
    constraint ck_operation_outbox_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
    constraint uq_operation_outbox_operation unique (operation_id)
);

comment on table operation_outbox is
    'PB-B4：派发指令 outbox（§2.8）——与 rca_operation PREPARED 同事务插入'
    '（消费→派发崩溃窗关闭）；1 operation 恰 1 outbox 行（RETRYABLE 重派=同行'
    '回 PENDING，B5 reconcile）；at-least-once + operation 状态机幂等';

-- 领取面（公平排序；租约过期回收）
create index ix_operation_outbox_claim on operation_outbox(available_at, created_at)
    where state = 'PENDING';
create index ix_operation_outbox_lease on operation_outbox(lease_until)
    where state = 'CLAIMED';

grant select, insert, update on operation_outbox to control_app;
