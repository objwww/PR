-- ============================================================================
-- V13 —— AM4 Run/Incident 预算账本（M4-08/09 同任务迁移；技术方案 v1.3 §6 预算/DoomLoopGuard）
--
-- 三段式预留语义（评审补强幂等后）：
--   reserve  = 行锁短事务原子预增（单语句 UPDATE ... AND consumed+units<=limit RETURNING，
--              超预算 0 行即拒）；网络调用期间不持任何锁——账本方法自含短事务，返回即出锁；
--   commit   = 服务端 usage 实扣平账（实际超估算允许软超限——已发生的真实消耗不可撤）；
--   release  = 发送前取消，全额退款；发送后取消 → PROVISIONAL（不立即按 input floor 平账，
--              等对账）；usage 缺失 → UNMATCHED（不伪造零退款）。
--   幂等业务键 UNIQUE(run_id, task_id, attempt_id, call_seq, budget_kind)——同键重试
--   读取同一笔 reservation，不重复预留。
--   REPORT（最终报告专项预算）不归属任何 task/attempt，用 nil-UUID 哨兵占位保持
--   幂等键 NOT NULL 简单（表达式唯一索引复杂度不值）。
--   TIME 预算不落账本——固定 deadline 语义留在纯逻辑 RunBudget.checkDeadline。
-- ============================================================================

-- 每(run,kind)原子计数行：reserve 是对这一行的单语句条件 UPDATE（行锁天然串行化）
create table run_budget_state (
    run_id         uuid        not null,
    budget_kind    varchar(16) not null,
    limit_units    bigint      not null,
    consumed_units bigint      not null default 0,
    updated_at     timestamptz not null default now(),
    constraint pk_run_budget_state primary key (run_id, budget_kind),
    constraint ck_run_budget_state_kind check (budget_kind in
        ('STEP', 'TOOL_CALL', 'EVIDENCE', 'SUBTASK', 'TOKEN', 'REPORT')),
    constraint ck_run_budget_state_limit check (limit_units >= 0),
    constraint ck_run_budget_state_consumed check (consumed_units >= 0)
);

comment on table run_budget_state is
    'AM4 M4-08 Run 级预算原子计数（reserve 单语句读-判-扣-写；REPORT=报告专项预算）';

-- 预留生命周期账本：幂等键唯一 + 五态迁移（RESERVED→COMMITTED/RELEASED；→PROVISIONAL→COMMITTED/UNMATCHED）
create table run_budget_entry (
    id              uuid        not null,
    run_id          uuid        not null,
    task_id         uuid        not null,
    attempt_id      uuid        not null,
    call_seq        bigint      not null,
    budget_kind     varchar(16) not null,
    state           varchar(16) not null,
    reserved_units  bigint      not null,
    committed_units bigint,
    created_at      timestamptz not null default now(),
    settled_at      timestamptz,
    constraint pk_run_budget_entry primary key (id),
    constraint uq_run_budget_entry_key unique (run_id, task_id, attempt_id, call_seq, budget_kind),
    constraint ck_run_budget_entry_kind check (budget_kind in
        ('STEP', 'TOOL_CALL', 'EVIDENCE', 'SUBTASK', 'TOKEN', 'REPORT')),
    constraint ck_run_budget_entry_state check (state in
        ('RESERVED', 'COMMITTED', 'RELEASED', 'PROVISIONAL', 'UNMATCHED')),
    constraint ck_run_budget_entry_reserved check (reserved_units > 0),
    constraint ck_run_budget_entry_settled check (
        (state = 'RESERVED'  and committed_units is null and settled_at is null) or
        (state = 'COMMITTED' and committed_units is not null and committed_units >= 0
                             and settled_at is not null) or
        (state = 'RELEASED'  and committed_units is null and settled_at is not null) or
        (state = 'PROVISIONAL' and committed_units is null and settled_at is null) or
        (state = 'UNMATCHED' and committed_units is null and settled_at is not null))
);

create index ix_run_budget_entry_stale on run_budget_entry (created_at)
    where state in ('RESERVED', 'PROVISIONAL');

comment on table run_budget_entry is
    'AM4 M4-08 预算三段式预留账本（幂等业务键；崩溃对账面=RESERVED/PROVISIONAL 滞留行）';

-- M4-09 IncidentBudget 跨 Run 滚动窗口账本：admission 时按窗口 SUM 判定（低频路径，
-- 不维护计数行）；窗口滚动=按 created_at 过滤（24h/7d 独立判定）
create table incident_budget_entry (
    id           uuid        not null,
    incident_id  uuid        not null,
    run_id       uuid        not null,
    window_kind  varchar(8)  not null,
    units        bigint      not null,
    created_at   timestamptz not null default now(),
    constraint pk_incident_budget_entry primary key (id),
    constraint ck_incident_budget_window check (window_kind in ('24H', '7D')),
    constraint ck_incident_budget_units check (units > 0)
);

create index ix_incident_budget_window on incident_budget_entry (incident_id, window_kind, created_at);

comment on table incident_budget_entry is
    'AM4 M4-09 Incident 跨 Run 窗口预算（admission 预留；存储不可用 fail-closed）';

grant select, insert, update on run_budget_state, run_budget_entry, incident_budget_entry
    to control_app;
