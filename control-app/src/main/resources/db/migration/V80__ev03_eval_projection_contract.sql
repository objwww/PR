-- ============================================================================
-- V80 —— EV-03 评测投影契约扩展（docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md §5.1/§5.3）
--   eval_run.display_name   可读名称（可空；写入归 EV-04 幂等创建命令，本期投影如实 null）
--   eval_run.mode           实验模式 E/B/L（可空；同上——无真实数据源时不编造）
--   eval_phase_event        阶段事件表（§5.1：阶段由 worker 落事件，后端投影、前端不猜；
--                           写面归 EV-04 持久化 worker，本期读面空表 → phase 如实 null）
--
-- 号段纪律：V46~V59 = R7，V60~V79 = EN，EV 线自 V80 起。
--
-- 授权分工沿 V10/V45：eval_app = eval-runner 独立写身份（EV-04 worker 落事件、
--   创建命令回填 display_name/mode）；control_app 只读投影（/api/eval/** 面）；
--   生产其他角色零权限。display_name/mode 的 control_app 读面由 V45 表级 SELECT 覆盖
--   （PG 表级 grant 及于后增列），本迁移不重复授。
-- ============================================================================

-- ---------- 1. eval_run：可读名称 + 模式（均可空，诚实留空） ----------

alter table eval_run add column display_name text;
alter table eval_run add column mode varchar(8);

alter table eval_run add constraint ck_eval_run_mode
    check (mode is null or mode in ('E', 'B', 'L'));

-- ---------- 2. eval_phase_event：阶段事件（insert-only；一事件一行，不覆盖） ----------
-- 阶段词汇表按 EvalBatchRunner 真实编排落：PREPARING（环境/上一轮现场核查）、
-- INJECTING（故障注入）、AWAITING_ALERT（等告警）、AWAITING_RCA（等调查 Run）、
-- SCORING（评分）、FINALIZING（聚合回填）、RECOVERING（L 模式转恢复，EV-04 复用 drill 责任）。
-- stageEnteredAt = entered_at；lastProgressAt 不走本表（投影自 eval_case_result.created_at
-- 真实落档时刻）；leaseHeartbeatAt 无租约数据源（EV-04 前如实 null）。

create table eval_phase_event (
    id          uuid primary key,
    eval_run_id uuid not null references eval_run(id),
    phase       varchar(16) not null,
    entered_at  timestamptz not null,
    worker_id   text,                    -- 落事件的 worker 身份（失联判定的对账锚）
    detail      jsonb,                   -- 阶段附加事实（卡因等；无则 NULL）
    created_at  timestamptz not null default now(),

    constraint ck_eval_phase_event_phase check (phase in
        ('PREPARING','INJECTING','AWAITING_ALERT','AWAITING_RCA',
         'SCORING','FINALIZING','RECOVERING'))
);

-- 投影路径：每 run 取最新一事件（entered_at DESC, id DESC 稳定落点）
create index ix_eval_phase_event_run on eval_phase_event(eval_run_id, entered_at desc, id desc);

-- ---------- 3. 授权（V10 同构；IT 干净库缺角色兜底同 V9） ----------

do $$
begin
    if not exists (select from pg_roles where rolname = 'eval_app') then
        create role eval_app login;
    end if;
end
$$;

-- eval_app：阶段事件写面（EV-04 worker）+ 名称/模式回填（EV-04 幂等创建命令）
grant select, insert on eval_phase_event to eval_app;
grant update (display_name, mode) on eval_run to eval_app;

-- control_app：/api/eval/** 只读投影面（V45 同律，只授 SELECT）
grant select on eval_phase_event to control_app;

-- 显式冻结（V10 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on eval_phase_event from publisher_app, notify_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on eval_phase_event from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on eval_phase_event from chaos_admin_app;
    end if;
end
$$;
