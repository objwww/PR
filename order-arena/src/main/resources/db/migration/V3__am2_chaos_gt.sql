-- ============================================================================
-- V3 —— AM2 arena 域：chaos 会话 / ground truth / chaos 事件（M2-06）
--   设计依据：AM2 v3.0 §6.5（DB 权威开关，fail-closed，激活事务四约束）+ C-3（角色拆分）
--   + C-5（GT append-only 契约）。
--
-- 激活事务（arena-chaos-admin 单事务内）：
--   INSERT ground_truth_scenario + INSERT oa_chaos_session(ACTIVE) + INSERT oa_chaos_event
--   COMMIT 后 ACTIVE 才对业务注入点可见——三写任一点失败 = 全部不可见（零半态）。
--
-- 四约束（§6.5 冻结，逐项 IT）：
--   ① scenario_id 唯一（激活幂等/重放保护；一次激活 = 一个唯一场景实例）
--   ② 同 fault_type+target 最多一个 ACTIVE（部分唯一索引；RECOVERING 视同占位）
--   ③ TTL 上下界：30s ≤ ttl ≤ 7200s 且 expires_at > created_at
--   ④ ACTIVE 必须存在 ground truth（DEFERRED 约束触发器，事务提交时校验）
--
-- 角色矩阵（C-3）：
--   chaos_admin_app  session RW（CAS 迁移）+ event INSERT/SELECT + GT INSERT/SELECT（禁 UPDATE/DELETE）
--   arena_app        仅 session SELECT（fail-closed 开关读面）；GT 零权限（INV-AM2-6）
--   eval_app         GT/session/event SELECT（评测读）
--   control_app / publisher_app / PUBLIC  零权限（GT 对告警链路不可见 = 硬指标）
-- ============================================================================

-- ---------- 1. ground_truth_scenario（C-5 全字段，append-only） ----------
create table arena.ground_truth_scenario (
    id                    uuid primary key,
    schema_version        integer not null check (schema_version >= 1),
    dataset_version       text not null,
    scenario_id           text not null,
    activation_generation integer not null default 0 check (activation_generation >= 0),
    config_digest         char(64) not null,   -- sha256(场景配置规范序列化)
    payload_digest        char(64) not null,   -- sha256(expected_root_cause/claims 载荷)
    applicable_scope      text not null,       -- 生效范围（arena / fault 域限定）
    valid_from            timestamptz not null,
    valid_until           timestamptz,
    review_status         varchar(16) not null default 'DRAFT',
    created_at            timestamptz not null default now(),

    constraint ck_gt_review_status check (review_status in ('DRAFT','CONFIRMED')),
    constraint ck_gt_validity check (valid_until is null or valid_until > valid_from)
);

create index ix_gt_scenario on arena.ground_truth_scenario(scenario_id, activation_generation);

-- C-5：append-only 禁 UPDATE，纠错用新版本行。触发器连 owner 一并拦死（比 revoke 更硬）。
create function arena.fn_gt_append_only() returns trigger as $$
begin
    raise exception 'ground_truth_scenario is append-only (C-5): insert a new version row instead';
end;
$$ language plpgsql;

create trigger trg_gt_append_only
    before update or delete on arena.ground_truth_scenario
    for each row execute function arena.fn_gt_append_only();

-- ---------- 2. oa_chaos_session（故障场景权威状态） ----------
create table arena.oa_chaos_session (
    id             uuid primary key,
    scenario_id    text not null,
    fault_type     varchar(8) not null,        -- F1=幂等失效 F2=状态回跳 F3=超时未知
    target         text,                       -- selector：correlation 前缀 / 精确订单号（NULL=全域）
    ttl_seconds    integer not null,
    operator       text not null,
    config_digest  char(64) not null,
    state          varchar(16) not null default 'PREPARED',
    generation     bigint not null default 0 check (generation >= 0),
    created_at     timestamptz not null default now(),
    expires_at     timestamptz not null,
    updated_at     timestamptz not null default now(),

    constraint ck_chaos_fault_type check (fault_type in ('F1','F2','F3')),
    constraint ck_chaos_state check (state in ('PREPARED','ACTIVE','RECOVERING','CLOSED')),
    -- 约束③：TTL 上下界
    constraint ck_chaos_ttl_bounds check (ttl_seconds between 30 and 7200),
    constraint ck_chaos_ttl_positive check (expires_at > created_at)
);

-- 约束①：scenario_id 唯一
alter table arena.oa_chaos_session
    add constraint uq_chaos_scenario unique (scenario_id);

-- 约束②：同 fault_type+target 最多一个 ACTIVE（RECOVERING 视同占位——修复未收口不得重开）
create unique index uq_chaos_one_active
    on arena.oa_chaos_session(fault_type, coalesce(target, ''))
    where state in ('ACTIVE','RECOVERING');

-- 约束④：ACTIVE 必须存在 ground truth（DEFERRED：提交时校验，与 GT 同事务即过、缺 GT 即败）
create function arena.fn_assert_active_has_gt() returns trigger as $$
begin
    if new.state = 'ACTIVE' and not exists (
        select 1 from arena.ground_truth_scenario gt
        where gt.scenario_id = new.scenario_id) then
        raise exception 'chaos session % cannot be ACTIVE without ground truth (C-5/§6.5)',
            new.scenario_id;
    end if;
    return new;
end;
$$ language plpgsql;

create constraint trigger trg_chaos_active_gt
    after insert or update of state on arena.oa_chaos_session
    deferrable initially deferred
    for each row execute function arena.fn_assert_active_has_gt();

-- TTL 过期清扫面（chaos-admin reaper / arena switchboard 读面共用）
create index ix_chaos_expiry on arena.oa_chaos_session(expires_at)
    where state in ('ACTIVE','RECOVERING');

-- ---------- 3. oa_chaos_event（会话生命周期审计，只增） ----------
create table arena.oa_chaos_event (
    id          uuid primary key,
    session_id  uuid not null references arena.oa_chaos_session(id),
    event_type  varchar(24) not null,
    detail      jsonb,
    occurred_at timestamptz not null default now(),

    constraint ck_chaos_event_type
        check (event_type in ('ACTIVATED','DEACTIVATED','TTL_EXPIRED',
                              'RECOVERY_COMPLETED','STARTUP_REAPED'))
);

create index ix_chaos_event_session on arena.oa_chaos_event(session_id, occurred_at);

-- ---------- 4. 授权（C-3 矩阵） ----------
-- chaos_admin_app：权威写者（GT 只增 + session CAS + event 只增）
grant select, insert on arena.ground_truth_scenario to chaos_admin_app;
grant select, insert, update on arena.oa_chaos_session to chaos_admin_app;
grant select, insert on arena.oa_chaos_event to chaos_admin_app;

-- arena_app：业务侧只读开关面（fail-closed 判定）；GT/事件零权限
grant select on arena.oa_chaos_session to arena_app;

-- eval_app：评测只读（GT 报告封存后读取，INV-AM2-6 正面）
grant select on arena.ground_truth_scenario to eval_app;
grant select on arena.oa_chaos_session to eval_app;
grant select on arena.oa_chaos_event to eval_app;

-- GT 对告警链路与存量角色零权限（INV-AM2-6 反面硬指标：control_app SELECT 必败）
revoke all on arena.ground_truth_scenario from control_app;
revoke all on arena.ground_truth_scenario from publisher_app;
revoke all on arena.ground_truth_scenario from public;
revoke all on arena.oa_chaos_session, arena.oa_chaos_event from control_app;
revoke all on arena.oa_chaos_session, arena.oa_chaos_event from publisher_app;
revoke all on arena.oa_chaos_session, arena.oa_chaos_event from public;
