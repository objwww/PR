-- PB-B3（Phase B 第三片）：Resource Coordinator——资源 mutation 锁。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §2.9（R3）：
--   * 漏洞关闭：锁 TTL 到期<b>不</b>回收让锁——Operation UNKNOWN 时 TTL 到期释放
--     会制造 external side-effect zombie（A 的 restart 仍在进行，B 拿锁执行 scale）；
--   * 释放矩阵：锁释放受 Operation 状态约束（VERIFIED/COMPLETED/FAILED_CONFIRMED/
--     CANCELLED_BEFORE_DISPATCH 才可释放；UNKNOWN/RECONCILING/ESCALATED = BUSY）；
--   * TTL 的唯一职责 = 过期把 HELD 标 ORPHANED（持有者孤儿化 → 驱动 reconcile），
--     锁行保留、资源持续 BUSY（B 组不变量：UNKNOWN/RECONCILING 期间不让渡）；
--   * 命名：resource_epoch = 该资源上第几波 mutation（与 run lease_epoch 无关），
--     计数独立成表——锁释放（删行）不丢代数。

-- ---------- 1. resource_mutation_lock：锁行存在 = BUSY（含 ORPHANED） ----------

create table resource_mutation_lock (
    resource_uid    text primary key,       -- 锁粒度 = canonical 资源（resolver 身份）
    operation_id    uuid not null references rca_operation(operation_id),
    run_id          uuid not null,
    resource_epoch  bigint not null,        -- acquire 时自 counter 领取（单调递增）
    state           varchar(16) not null default 'HELD',
    ttl_expires_at  timestamptz not null,   -- 只驱动孤儿化，绝不让渡（§2.9）
    acquired_at     timestamptz not null,
    orphaned_at     timestamptz,
    updated_at      timestamptz not null default now(),

    constraint ck_resource_lock_state check (state in ('HELD','ORPHANED')),
    constraint ck_resource_lock_orphaned check ((state = 'ORPHANED') = (orphaned_at is not null))
);

comment on table resource_mutation_lock is
    'PB-B3：资源 mutation 锁（§2.9）——行存在即 BUSY（ORPHANED 亦不让渡）；'
    '释放只走 releaseOnTerminalState（Operation 状态 ∈ 释放集的 SQL 闸）；'
    'TTL 过期仅 HELD→ORPHANED（孤儿化驱动 reconcile），锁行不删';

-- ---------- 2. resource_mutation_counter：代数独立于锁行生命周期 ----------

create table resource_mutation_counter (
    resource_uid    text primary key,
    last_epoch      bigint not null default 0
);

comment on table resource_mutation_counter is
    'PB-B3：resource_epoch 计数器——锁释放删行后代数仍单调；'
    '与 run_lease_epoch 无关（§2.9 命名澄清：一个管执行者世代，一个管资源 mutation 波次）';

-- ---------- 3. 授权 ----------

grant select, insert, update, delete on resource_mutation_lock to control_app;
grant select, insert, update on resource_mutation_counter to control_app;
