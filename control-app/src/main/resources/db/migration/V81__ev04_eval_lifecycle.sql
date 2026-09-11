-- ============================================================================
-- V81 —— EV-04 评测发起与生命周期（docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md
--   EV-04 卡 / §5.1 / §5.3 第二三行；EU09/EU10/EU14）
--   eval_run.launch_plan       发起计划快照（配置回看 §3.2；旧 CLI 跑批行如实 NULL）
--   eval_run.recovery_state    L 模式恢复核验分面：PENDING/RECOVERING/VERIFIED/FAILED
--                              （NULL = worker 未上报；读面按 mode 区分 UNKNOWN 与
--                              NOT_APPLICABLE，不编造）
--   eval_run.terminal_reason   失败/取消卡因（EU13 可读面；SUCCEEDED 恒 NULL）
--   eval_run_command           发起/取消持久化命令（V27 operator_command 同构：
--                              先持久化再生效；幂等锚 (command_type, idempotency_key)，
--                              同键同 payload 重放返回原命令、异 payload 由服务层判 409）
--
-- 号段纪律：V46~V59 = R7，V60~V79 = EN，EV 线自 V80 起（V80 = EV-03）。
--
-- 授权分工（V10/V27/V45/V80 同律）：
--   - control_app（/api/eval 写面 HTTP 身份）：命令表 select,insert——只能提交意图，
--     零 update/delete 开口（命令落库即冻结，生效面归 worker）；eval_run 仍只读（V45）；
--   - eval_app（worker 身份）：命令表 select + 列级 update（状态推进三列 + worker_id），
--     eval_run 增授 launch_plan/recovery_state/terminal_reason 三列写面
--     （display_name/mode 已由 V80 授）；
--   - publisher/notify/arena/chaos/public：显式 revoke 归零。
-- ============================================================================

-- ---------- 1. eval_run：发起计划快照 + 恢复分面 + 终态卡因 ----------

alter table eval_run add column launch_plan jsonb;
alter table eval_run add column recovery_state varchar(16);
alter table eval_run add column terminal_reason text;

alter table eval_run add constraint ck_eval_run_recovery_state
    check (recovery_state is null or recovery_state in
        ('PENDING', 'RECOVERING', 'VERIFIED', 'FAILED'));

comment on column eval_run.launch_plan is
    'EV-04 发起计划快照（displayName/mode/datasetVersion/candidate/budget）；旧 CLI 跑批行 NULL';
comment on column eval_run.recovery_state is
    'L 模式恢复核验：PENDING=待核验/RECOVERING=恢复中/VERIFIED=已核验/FAILED=核验失败；NULL=未上报';
comment on column eval_run.terminal_reason is
    'FAILED 终态卡因（如 cancelled_by_operator / worker_lost）；SUCCEEDED 恒 NULL';

-- ---------- 2. eval_run_command：发起/取消持久化命令（insert-only 正文） ----------
-- LAUNCH：eval_run_id 为预定 run 身份（worker 拾起时以其 insertRunning——
--   崩溃重放同键不产生第二个 run）；此时 run 行尚未存在，故不建 FK（诚实标注）。
-- CANCEL：eval_run_id 为目标 run；受理只表示"取消中"（读面 cancel_requested_at），
--   推进语义归 worker 检查点。

create table eval_run_command (
    id               uuid primary key,
    command_type     varchar(8) not null
                     check (command_type in ('LAUNCH', 'CANCEL')),
    eval_run_id      uuid not null,
    idempotency_key  varchar(128) not null,
    payload          jsonb not null default '{}'::jsonb
                     check (jsonb_typeof(payload) = 'object'),
    payload_hash     char(64) not null,      -- 计划 canonical 摘要（异 payload 409 判据）

    -- 状态单向推进：PENDING（已持久化待领取）→ CLAIMED（worker 已领）→
    -- DONE / FAILED（终态）；REJECTED 保留给服务层显式拒绝留痕
    state            varchar(16) not null default 'PENDING'
                     check (state in ('PENDING', 'CLAIMED', 'DONE', 'FAILED', 'REJECTED')),
    actor            varchar(64) not null,   -- 提交主体（认证面唯一来源）
    worker_id        text,                   -- 领取者身份（失联对账锚）
    created_at       timestamptz not null default now(),
    claimed_at       timestamptz,
    finished_at      timestamptz,

    constraint uq_eval_run_command_idem unique (command_type, idempotency_key)
);

comment on table eval_run_command is
    'EV-04 评测发起/取消持久化命令（先持久化再生效；幂等锚 (command_type,idempotency_key)；'
    || 'LAUNCH 的 eval_run_id 是预定 run 身份故无 FK）';

create index ix_eval_run_command_run on eval_run_command(eval_run_id, created_at);
create index ix_eval_run_command_pending on eval_run_command(state, created_at)
    where state = 'PENDING';

-- ---------- 3. 授权（V10/V27 同构；IT 干净库缺角色兜底同 V10） ----------

-- control_app：提交意图（insert）+ 读回（select；读面投影 cancel_requested_at 用）——
-- 零 update/delete，命令正文落库即冻结
grant select, insert on eval_run_command to control_app;

-- eval_app（worker）：领取/推进状态（列级开口，正文不可改）
grant select on eval_run_command to eval_app;
grant update (state, worker_id, claimed_at, finished_at) on eval_run_command to eval_app;

-- eval_app：eval_run 新增三列写面（display_name/mode 已于 V80 授）
grant update (launch_plan, recovery_state, terminal_reason) on eval_run to eval_app;

-- 显式冻结（V10 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on eval_run_command from publisher_app, notify_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on eval_run_command from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on eval_run_command from chaos_admin_app;
    end if;
end
$$;
