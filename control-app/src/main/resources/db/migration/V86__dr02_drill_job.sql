-- ============================================================================
-- V86 —— DR-02 故障演练持久化作业链（docs/告警-前端逐页体验改造与后期优化方案.md
--   第七章：§7.2 页面契约 / §7.3 后端接法 / §7.4 状态机与异常收口；DR-02 卡）
--   drill_job     演练作业：稳定身份 + 冻结模板/参数 + 状态机相位 + outcome 另存
--                 （CLOSED ≠ 成功）+ 幂等锚 + 同靶场活动占位（原子互斥）+ 停止面
--   drill_event   演练事件账本（insert-only）：相位迁移 / 预检结果 / 停止请求审计
--
-- 号段纪律：V80~V83、V85 已占用，V84 空置（EV-05 零迁移声明），DR-02 自 V86 起
--   （三线定死：R7=V46 起、EN=V60 起、EV/DR=V80 起）。
--
-- 语义纪律（§7.4 逐条落库）：
--   - 状态机：QUEUED→PRECHECK→INJECTING→OBSERVING→RECOVERING→VERIFYING→CLOSED；
--     注入前取消 = CANCELLED；一旦注入可能发生，停止/失败必先进 RECOVERING；
--     RECOVERY_FAILED 保留靶场占位并阻止下一场（部分唯一索引的活动集含它）；
--     CLOSED 唯一来源 = VERIFYING（应用层 DrillLifecycle 收口，事件账本可审计）；
--   - outcome 另存：PASS/FAIL/INCONCLUSIVE 仅在 CLOSED 时落值，CLOSED 必带 outcome
--     （CHECK 双向钉死）——CLOSED 只表示恢复核验完成，不冒充演练目标达成；
--   - 环境互斥：uq_drill_job_active_env 部分唯一索引 = 数据库原子占位，
--     页面与 CLI 同一入口，不同 faultType 同靶场同样互斥（§7.3）；
--   - 幂等：idempotency_key 全表唯一（同键同 payload 重放返回原作业，异 payload
--     服务层判 409）；stop_idempotency_key 唯一（重复停止幂等，DU14）。
--
-- 授权分工（V10/V45/V81 同律）：
--   - control_app（/api/drills 面 HTTP 身份）：drill_job select,insert + 列级
--     update(stop_requested_at, stop_idempotency_key)——只能提交作业与停止意图，
--     状态机推进零开口；drill_event select,insert（停止请求审计行）；
--   - eval_app（worker 身份，DR-02 执行器复用评测执行身份所在 worker，§7.3）：
--     drill_job select + 列级 update（相位/outcome/卡因/租约/关联列），正文列
--     （场景/参数/幂等键/发起人）零开口；drill_event select,insert；
--   - publisher/notify/arena/chaos/public：显式 revoke 归零。
-- ============================================================================

-- ---------- 1. drill_job：演练作业 ----------

create table drill_job (
    id                    uuid primary key,
    scenario_id           varchar(32) not null,    -- 冻结场景身份（S1~S5）
    scenario_name         varchar(128) not null,   -- 冻结展示名（运行中模板改名不影响本场）
    template_digest       char(64) not null,       -- 冻结模板目录内容摘要（启动时冻结，§7.4）
    target_env            varchar(64) not null,    -- 靶场（后端白名单值，不接收自由输入）
    operator              varchar(64) not null,    -- 发起人（认证面唯一来源，不采信自报）

    state                 varchar(16) not null default 'QUEUED'
                          check (state in ('QUEUED', 'PRECHECK', 'INJECTING', 'OBSERVING',
                              'RECOVERING', 'VERIFYING', 'CLOSED', 'CANCELLED',
                              'FAILED', 'RECOVERY_FAILED')),
    outcome               varchar(16)              -- 演练目标结论（另存；CLOSED≠成功）
                          check (outcome is null or outcome in
                              ('PASS', 'FAIL', 'INCONCLUSIVE')),
    terminal_reason       text,                    -- 失败/取消/恢复失败卡因（可读面）

    params                jsonb not null           -- 冻结参数与派生窗口（durationSeconds/
                          check (jsonb_typeof(params) = 'object'),   -- trafficScale/
                                                     -- linkedEvalVersion/ttlSeconds 等）
    payload_hash          char(64) not null,       -- 计划 canonical 摘要（异 payload 409 判据）
    idempotency_key       varchar(128) not null,
    stop_idempotency_key  varchar(128),            -- 停止幂等锚（重复停止幂等，DU14）
    stop_requested_at     timestamptz,             -- 停止受理时刻（受理≠恢复完成）

    worker_id             text,                    -- 领取者身份（失联对账锚）
    claimed_at            timestamptz,
    revision              bigint not null default 0, -- 状态推进 CAS 对账（租约过期≠可重做）

    related_incident_id   uuid,                    -- 关联告警（DR-06 回填；NULL=尚未关联）
    related_run_id        uuid,                    -- 关联调查 Run（DR-06 回填；NULL=尚未关联）

    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    closed_at             timestamptz,

    constraint uq_drill_job_idem unique (idempotency_key),
    constraint uq_drill_job_stop_idem unique (stop_idempotency_key),
    -- CLOSED 语义钉死：closed_at 与 outcome 与 CLOSED 同生同灭（CLOSED≠成功，outcome 另存）
    constraint ck_drill_job_closed_shape check (
        (state = 'CLOSED' and closed_at is not null and outcome is not null)
        or (state <> 'CLOSED' and closed_at is null and outcome is null))
);

comment on table drill_job is
    'DR-02 故障演练持久化作业（§7.4 状态机；outcome 另存 CLOSED≠成功；同靶场活动占位=部分唯一索引原子互斥；停止受理只表示恢复中）';

-- 环境互斥原子占位（§7.3）：活动集含 RECOVERY_FAILED——恢复未核验前阻止下一场（DU15）
create unique index uq_drill_job_active_env on drill_job(target_env)
    where state in ('QUEUED', 'PRECHECK', 'INJECTING', 'OBSERVING',
                    'RECOVERING', 'VERIFYING', 'RECOVERY_FAILED');

create index ix_drill_job_list on drill_job(created_at desc, id desc);
create index ix_drill_job_claim on drill_job(state, created_at, id)
    where state = 'QUEUED';

-- ---------- 2. drill_event：演练事件账本（insert-only） ----------

create table drill_event (
    id           uuid primary key,               -- 应用生成稳定 id（重放对账锚）
    drill_id     uuid not null references drill_job(id),
    seq          bigint generated always as identity,  -- 单调序=事件游标锚
    event_type   varchar(32) not null
                 check (event_type in ('PHASE_TRANSITION', 'PRECHECK_RESULT',
                     'STOP_REQUESTED', 'OUTCOME_RECORDED', 'WORKER_NOTE')),
    from_state   varchar(16),
    to_state     varchar(16),
    actor        varchar(64) not null,           -- 事件主体（operator 或 worker id）
    payload      jsonb not null default '{}'::jsonb
                 check (jsonb_typeof(payload) = 'object'),
    created_at   timestamptz not null default now()
);

comment on table drill_event is
    'DR-02 演练事件账本（insert-only：相位迁移/预检结果/停止请求/结论落档/执行注记；seq 单调序=事件游标锚）';

create index ix_drill_event_drill on drill_event(drill_id, seq);

-- ---------- 3. 授权（V10/V45/V81 同构；IT 干净库缺角色兜底同 V10） ----------

-- control_app：提交作业（insert）+ 读面（select）+ 停止意图两列写面——状态机推进零开口
grant select, insert on drill_job to control_app;
grant update (stop_requested_at, stop_idempotency_key) on drill_job to control_app;
grant select, insert on drill_event to control_app;

-- eval_app（worker）：领取/相位推进/结论与关联回填（列级开口，正文列不可改）
grant select on drill_job to eval_app;
grant update (state, outcome, terminal_reason, worker_id, claimed_at, revision,
    related_incident_id, related_run_id, closed_at, updated_at) on drill_job to eval_app;
grant select, insert on drill_event to eval_app;

-- drill_event.seq 是 identity 列：insert 走 DEFAULT，无需序列授权

-- 显式冻结（V10 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on drill_job from publisher_app, notify_app, public;
revoke all on drill_event from publisher_app, notify_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on drill_job from arena_app;
        revoke all on drill_event from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on drill_job from chaos_admin_app;
        revoke all on drill_event from chaos_admin_app;
    end if;
end
$$;
