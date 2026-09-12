-- ============================================================================
-- V95 —— DR-05 flagd 恢复台账（docs/告警-前端逐页体验改造与后期优化方案.md
--   §7.4 Flagd 段「记录修改前值和版本，只在当前值仍属于本次写入时条件恢复」+
--   §7.5 DR-05 卡「保存实际原值与写入版本、条件恢复、冲突不覆盖、worker 重启后清扫」）
--   flagd_restore_ledger   flagd 激活的恢复台账：实际原值/原代际 + 本次写入值/写入
--                          代际 + 作业级截止；条件恢复三态收口（RESTORED/CONFLICT/
--                          UNKNOWN），OPEN/UNKNOWN = 可恢复面（清扫重试入口）
--
-- 号段纪律：V89=EN 已上 195，V90~V93 被 R 线在飞占用，V94 空置待认领——DR-05 自 V95 起
--   （三线定死：R7=V46 起、EN=V60 起、EV/DR=V80 起，同记于增强线方案 §6.1）。
--
-- 语义纪律（§7.4 逐条落库）：
--   - 原值如实面：original_variant/original_generation 可空 = 激活时读取失败，
--     不编造原值；恢复目标 = 实际原值优先，缺失才以模板 baseline_variant 兜底；
--   - 代际令牌 = 服务端判代标识（flagd-admin 落码面 = 该 flag 条目 canonical JSON
--     的 sha256，任何改写必变）；服务端不提供 = NULL，条件恢复退化纯值比对；
--   - 状态机：OPEN（写入成功待恢复）→ RESTORED / CONFLICT / UNKNOWN；
--     UNKNOWN（读不到当前值未盲写）保持可恢复面，超 deadline 由 FlagdRestoreSweeper
--     重试；RESTORED/CONFLICT 终态不回开（CHECK + 应用层 CAS 双保险）；
--   - 正文列（flag/原值/写入值/截止）落账即冻结：eval_app 仅 state/state_reason/
--     updated_at 三列 update 开口。
--
-- 授权（V10/V86 同律）：eval_app（driver + worker 同一身份）select,insert +
--   列级 update；control_app 零授权（冲突面本批经 drill_event/告警链呈现，不开
--   control 读面）；publisher/notify/PUBLIC 显式 revoke 归零。
-- ============================================================================

create table flagd_restore_ledger (
    id                  uuid primary key,
    flag                varchar(128) not null,     -- 目标 flag（BA-19 单键）
    scenario_id         varchar(64) not null,      -- 冻结场景身份（S1/S2）
    round_no            integer not null,          -- 评测轮次（drill 面恒 1）

    original_variant    varchar(128),              -- 实际原值（写入前读取；失败=NULL 如实）
    original_generation varchar(128),              -- 原代际令牌（服务端不给=NULL）
    applied_variant     varchar(128) not null,     -- 本次写入值（服务端回执面）
    applied_generation  varchar(128),              -- 写入代际令牌（条件恢复的他者改写判据）
    baseline_variant    varchar(128) not null,     -- 模板基线（原值缺失时的恢复兜底）

    state               varchar(16) not null default 'OPEN'
                        check (state in ('OPEN', 'RESTORED', 'CONFLICT', 'UNKNOWN')),
    state_reason        text,                      -- 收口卡因（冲突现值/读取失败等可读面）

    deadline_at         timestamptz not null,      -- 作业级截止（激活时刻+模板全窗口）
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

comment on table flagd_restore_ledger is
    'DR-05 flagd 恢复台账（§7.4/§7.5：实际原值+写入代际落账；条件恢复三态 RESTORED/CONFLICT/UNKNOWN；OPEN/UNKNOWN=可恢复面，超 deadline 由 worker 清扫重试）';

-- 可恢复面扫描（按 flag 对账 + 截止清扫）
create index ix_flagd_restore_flag_open on flagd_restore_ledger(flag, created_at desc)
    where state in ('OPEN', 'UNKNOWN');
create index ix_flagd_restore_deadline on flagd_restore_ledger(deadline_at)
    where state in ('OPEN', 'UNKNOWN');

-- ---------- 授权（V10/V86 同构） ----------

-- eval_app：driver 激活落账 + driver/sweeper 条件恢复收口（列级开口，正文列零写面）
grant select, insert on flagd_restore_ledger to eval_app;
grant update (state, state_reason, updated_at) on flagd_restore_ledger to eval_app;

-- 显式冻结（V10 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on flagd_restore_ledger from publisher_app, notify_app, control_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on flagd_restore_ledger from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on flagd_restore_ledger from chaos_admin_app;
    end if;
end
$$;
