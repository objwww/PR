-- ============================================================================
-- V4 —— AM2 arena 域：F3 对账租约 + DomainProbe 事实表 + 注入审计（M2-20/21）
--   设计依据：C-7（探测保留末值、永不伪装恢复、episode 语义）+ AM2 v3.0 §6.6/6.7。
--
-- 1) oa_payment_record 增补 F3 对账租约列：RECONCILING 状态的持有者与租约期——
--    双 reconciler 互斥靠 CAS（UNKNOWN→RECONCILING）+ 租约；reconciler 崩溃后
--    租约过期可被重领（"SIGKILL 后续跑"，全程无 sleep 表达状态）。
-- 2) oa_probe_finding：探测发现的 episode 台账——(type, entity) 打开即一个 episode，
--    修复关闭（resolved_at），复发开新 episode_no（"修复后复发重新计一次"）。
-- 3) oa_injection_audit：F1/F2 注入与恢复取证（arena 自己的表，不动 chaos_event——
--    C-3 角色拆分：arena 对 chaos 域只有 session SELECT）。chaos-admin 依本表判定
--    RECOVERING→CLOSED（只读）。
-- ============================================================================

-- ---------- 1. F3 对账租约（ALTER 而非重建：V1~V3 未部署过任何环境，但纪律仍按追加） ----------
alter table arena.oa_payment_record
    add column reconcile_owner text,
    add column reconcile_lease_until timestamptz;

-- 重领扫描面：UNKNOWN 超时待领 + RECONCILING 租约过期
create index ix_payment_reconcile on arena.oa_payment_record(result, reconcile_lease_until);

-- ---------- 2. oa_probe_finding（C-7 episode 台账） ----------
create table arena.oa_probe_finding (
    id               uuid primary key,
    finding_type     varchar(24) not null,      -- STUCK_ORDER / DUPLICATE_ORDER / STATE_VIOLATION
    entity_id        text not null,             -- orderId / intentId（违规主体）
    violation_digest char(64) not null,         -- 违规形态指纹（同 episode 内形态演进可追踪）
    episode_no       integer not null check (episode_no >= 1),
    first_seen_at    timestamptz not null default now(),
    last_seen_at     timestamptz not null default now(),
    resolved_at      timestamptz,
    detail           jsonb,

    constraint ck_probe_finding_type
        check (finding_type in ('STUCK_ORDER','DUPLICATE_ORDER','STATE_VIOLATION')),
    -- episode 开着就不许有 resolved_at；关了必须有
    constraint ck_probe_episode check ((resolved_at is null) or (resolved_at >= first_seen_at))
);

-- 同 (type, entity) 的 episode 序号唯一（复发 = 新行新号）
create unique index uq_probe_episode
    on arena.oa_probe_finding(finding_type, entity_id, episode_no);

-- 打开 episode 扫描面（gauge 计数与关闭判定共用）
create index ix_probe_open on arena.oa_probe_finding(finding_type)
    where resolved_at is null;

-- ---------- 3. oa_injection_audit（F1/F2 注入/恢复取证，只增） ----------
create table arena.oa_injection_audit (
    id          uuid primary key,
    session_id  uuid not null references arena.oa_chaos_session(id),
    fault_type  varchar(8) not null,
    order_id    uuid,                           -- F2 逐单注入；F1 会话级恢复为 NULL
    action      varchar(16) not null,           -- INJECTED / RECOVERED
    detail      jsonb,
    occurred_at timestamptz not null default now(),

    constraint ck_inj_fault_type check (fault_type in ('F1','F2','F3')),
    constraint ck_inj_action check (action in ('INJECTED','RECOVERED'))
);

-- 注入幂等锚：同会话同订单同动作至多一次（重复注入扫描安全重入）
create unique index uq_injection_once on arena.oa_injection_audit(session_id, order_id, action);

-- ---------- 4. 授权（C-3 矩阵延伸） ----------
-- arena_app：探测与审计的唯一写者
grant select, insert, update on arena.oa_probe_finding, arena.oa_injection_audit to arena_app;
-- F3 租约列随表授权（oa_payment_record 的 update 已在 V1）

-- chaos_admin_app：只读注入审计（RECOVERING→CLOSED 判定面）；业务事实零权限不破 C-3
grant select on arena.oa_injection_audit to chaos_admin_app;

-- eval_app：SELECT 已由 default privileges 覆盖（V1 末尾），此处显式声明防漂移
grant select on arena.oa_probe_finding, arena.oa_injection_audit to eval_app;

-- 防漂移显式冻结
revoke all on arena.oa_probe_finding, arena.oa_injection_audit from control_app;
revoke all on arena.oa_probe_finding, arena.oa_injection_audit from publisher_app;
revoke all on arena.oa_probe_finding, arena.oa_injection_audit from public;
