-- ============================================================================
-- V27 —— AM5 M5-14 运维命令账本（alert 域）
--   operator_command  Cancel/Hint/Feedback 命令：先持久化再生效（INV-AM5-7）——
--                     命令行是审计真相源（PERSISTED→APPLIED/REJECTED_* 单向推进）；
--                     幂等锚 uq (run_id, command_type, idempotency_key)——同键重放
--                     返回原命令行（含原拒绝态）；Hint 文本标 UNTRUSTED（进上下文
--                     的消费面读本表 payload，M5-14 只落账本与生效面）
--
-- 编号说明：落码方案 §M5-14① 新增号段（V26 顺延）。
-- 生效语义（落码方案 §M5-14③）：旧 expectedRevision → REJECTED_STALE（409）；
-- 越权（终态 Run 上的任何命令）→ REJECTED_FORBIDDEN（403）；FUT-33 断线不改
-- Run 状态——持久化先于生效，生效前崩溃重放同键续走 apply。
-- ============================================================================

-- ---------- 1. operator_command ----------

create table operator_command (
    id               uuid primary key,
    run_id           uuid not null references rca_run(id),
    command_type     varchar(16) not null
                     check (command_type in ('CANCEL','HINT','FEEDBACK')),
    idempotency_key  varchar(128) not null,
    expected_revision bigint not null,
    payload          jsonb not null default '{}'::jsonb
                     check (jsonb_typeof(payload) = 'object'),

    -- 状态单向推进：PERSISTED（已持久化未生效）→ APPLIED / REJECTED_STALE /
    -- REJECTED_FORBIDDEN（终态，重放原样返回）
    state            varchar(16) not null default 'PERSISTED'
                     check (state in ('PERSISTED','APPLIED','REJECTED_STALE','REJECTED_FORBIDDEN')),
    actor            varchar(64) not null,
    created_at       timestamptz not null default now(),
    applied_at       timestamptz,

    constraint uq_operator_command_idem unique (run_id, command_type, idempotency_key)
);

comment on table operator_command is
    'AM5 M5-14 运维命令账本（先持久化再生效——崩溃窗口命令行存续可重放；幂等锚 (run_id,command_type,idempotency_key)；expected_revision 锚 rca_run.last_event_seq）';

create index ix_operator_command_run on operator_command(run_id, created_at);

-- ---------- 2. 授权（账本行 insert 后正文不可改；终态推进只开口两列——V9 列级同构） ----------

grant select, insert on operator_command to control_app;
grant update (state, applied_at) on operator_command to control_app;
revoke delete on operator_command from control_app;

revoke all on operator_command
    from publisher_app, notify_app, eval_app, public;
