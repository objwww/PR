-- ============================================================================
-- V15 —— AM4 工具调用账本（M4-18；技术方案 v1.3 §6 错误语义/幂等键条目）
--
--   状态沿用既有四态 PENDING/SUCCESS/FAILED/UNKNOWN（不照搬 CrewAI 六分类）；
--   原因码冻结十码（INVALID_INPUT/POLICY_DENIED/TIMEOUT/RATE_LIMITED/AUTH_FAILED/
--   REMOTE_4XX/REMOTE_5XX/TRANSPORT_UNKNOWN/CANCELLED/REPLAY_MISS）。
--   唯一 operation_id = 主键；逻辑幂等键 UNIQUE(run,task,attempt,call_seq,tool)——
--   同一逻辑调用只落一行账。PENDING 先行（同事务先行落档，进程死后悬挂可查）；
--   终态 CAS 单向迁移（ck/settled 按 state 约束可空性）。
--   审批挂起态与 HMAC 审批 token 归 AM5，本迁移不引入。
-- ============================================================================

create table rca_tool_invocation (
    id            uuid        not null,
    run_id        uuid        not null,
    task_id       uuid        not null,
    attempt_id    uuid        not null,
    call_seq      bigint      not null,
    tool_name     varchar(64) not null,
    tool_version  varchar(32) not null,
    action_digest varchar(64) not null,
    state         varchar(16) not null,
    reason_code   varchar(24),
    started_at    timestamptz not null default now(),
    settled_at    timestamptz,
    constraint pk_rca_tool_invocation primary key (id),
    constraint fk_rca_tool_invocation_run foreign key (run_id) references rca_run (id),
    constraint uq_rca_tool_invocation_key
        unique (run_id, task_id, attempt_id, call_seq, tool_name),
    constraint ck_rca_tool_invocation_state check (state in
        ('PENDING', 'SUCCESS', 'FAILED', 'UNKNOWN')),
    constraint ck_rca_tool_invocation_reason check (reason_code in
        ('INVALID_INPUT', 'POLICY_DENIED', 'TIMEOUT', 'RATE_LIMITED', 'AUTH_FAILED',
         'REMOTE_4XX', 'REMOTE_5XX', 'TRANSPORT_UNKNOWN', 'CANCELLED', 'REPLAY_MISS')),
    constraint ck_rca_tool_invocation_success_no_reason
        check (state <> 'SUCCESS' or reason_code is null),
    constraint ck_rca_tool_invocation_settled check (
        (state = 'PENDING' and settled_at is null) or
        (state in ('SUCCESS', 'FAILED', 'UNKNOWN') and settled_at is not null))
);

create index ix_rca_tool_invocation_run on rca_tool_invocation (run_id, started_at);

comment on table rca_tool_invocation is
    'AM4 M4-18 只读工具调用账本（PENDING 先行/终态 CAS 单向/原因码冻结十码；审批态归 AM5）';

grant select, insert, update on rca_tool_invocation to control_app;
