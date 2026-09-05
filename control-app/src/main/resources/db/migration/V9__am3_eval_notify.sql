-- ============================================================================
-- V9 —— AM3 评测与通知出口（M3-03；docs/告警AM3-落码技术方案.md v1.1）
--   rca_investigation_result  每次 Holmes attempt 全程落档（成功与失败同权，INV-AM3-7）
--   rca_tool_call             Holmes 响应 tool_calls 落表（silence_penalty 数据源）
--   report_publication        报告发布状态机（报告本体不可变，BA-10② 消解）
--   notify_outbox             通知 outbox（at-least-once；唯一键防重 §6.5）
--
-- v1.1 评审采纳落点：
--   - generation 栅栏直挂（FUT-50）：investigation_result.observed_generation/payload_digest、
--     tool_call.run_id/observed_generation/schema_version/payload_digest/created_at——
--     禁止多层 JOIN 推导，晚到旧代写入由仓储 CAS 拒绝；
--   - 状态列分离：execution_status（执行结局）与 validation_status（结构验证结局）
--     两列独立——执行失败和结构失败不混装；
--   - STARTED 先行：attempt 铸造同事事务落档，进程在外调后落库前被杀也有悬挂行可查。
--
-- 角色分工（评审纠正）：notify_app 由本迁移创建并授权（不得提前到 AM2）；
--   arena_app/chaos_admin_app/eval_app 已由 AM2 创建（deploy/db/01-roles.sh，集群级角色）。
--   密码经 Flyway placeholder 注入（arena V1 同构），不落任何 yml/文件。
-- ============================================================================

-- ---------- 0. notify_app 角色（幂等；与 01-roles.sh 语义一致） ----------
do $$
begin
    if not exists (select from pg_roles where rolname = 'notify_app') then
        create role notify_app login password '${notify_password}';
    else
        alter role notify_app with login password '${notify_password}';
    end if;
end
$$;

-- ---------- 1. rca_investigation_result：调查记录契约（§6.2 冻结 DDL 语义 + v1.1 栅栏列） ----------
-- 只增不改（终态列除外）；一 attempt 恰一条（STARTED 先行 → 终态 CAS 回写）

create table rca_investigation_result (
    id                  uuid primary key,
    attempt_id          uuid not null unique references rca_attempt(id),
    run_id              uuid not null references rca_run(id),
    observed_generation integer not null,      -- FUT-50：铸造时的 run.generation 快照直挂
    schema_version      integer not null,

    -- 执行结局与结构验证结局分离（v1.1 评审：不混装）
    execution_status    varchar(16) not null,
    validation_status   varchar(32) not null,

    validation_errors   jsonb,                 -- REJECTED_* 拒绝原因链
    package_json        jsonb,                 -- 验证失败允许 NULL（§6.2 冻结语义）
    raw_artifact_ref    text,                  -- CAS 引用（脱敏原文）
    raw_digest          char(64),              -- 原文 SHA-256（脱敏前）
    payload_digest      char(64),              -- package_json 摘要（冻结输出/评分对齐锚）
    model               text,
    usage_json          jsonb,                 -- usage_missing 时为空

    created_at          timestamptz not null,  -- STARTED 落档时刻
    finished_at         timestamptz,           -- 终态回写时刻（终态列）

    constraint uq_rca_investigation_result_attempt unique (attempt_id),

    constraint ck_rca_ir_execution
        check (execution_status in ('STARTED','SUCCEEDED','FAILED','TIMEOUT','UNKNOWN','CANCELLED')),
    constraint ck_rca_ir_validation
        check (validation_status in ('NOT_VALIDATED','STRUCTURE_VALIDATED',
                                     'REJECTED_MALFORMED','REJECTED_OVERSIZE',
                                     'REJECTED_SCHEMA_VERSION','REJECTED_SCHEMA_MISMATCH')),
    constraint ck_rca_ir_schema_version
        check (schema_version > 0),
    -- STARTED 是未完成态；其余为终态且必有 finished_at
    constraint ck_rca_ir_execution_lifecycle
        check ((execution_status = 'STARTED' and finished_at is null)
            or (execution_status <> 'STARTED' and finished_at is not null)),
    -- 只有结构验证通过的记录才有可评分包（INV-AM3-7 的 DB 面）
    constraint ck_rca_ir_validated_has_package
        check (validation_status <> 'STRUCTURE_VALIDATED' or package_json is not null)
);

create index ix_rca_ir_run on rca_investigation_result(run_id, created_at desc);
-- 崩溃回收：悬挂 STARTED 扫描入口（外调后落库前被杀 → 回收标 UNKNOWN）
create index ix_rca_ir_started on rca_investigation_result(created_at)
    where execution_status = 'STARTED';

-- ---------- 2. rca_tool_call：tool_calls 落表（只增不改） ----------

create table rca_tool_call (
    investigation_result_id uuid not null references rca_investigation_result(id),
    tool_call_id            text not null,
    sequence_no             integer not null check (sequence_no >= 1),
    tool_name               text not null,
    status                  varchar(20)
        check (status is null or status in ('SUCCESS','ERROR','NO_DATA','APPROVAL_REQUIRED')),
    params_digest           char(64),
    result_digest           char(64),
    started_at              timestamptz,
    finished_at             timestamptz,

    -- v1.1 栅栏直挂（FUT-50）：与 result 同值的 run/generation/schema/digest 冗余落列，
    -- 旧 observed_generation 的写入被仓储 CAS 拒绝（晚到不覆盖）
    run_id                  uuid not null references rca_run(id),
    observed_generation     integer not null,
    schema_version          integer not null,
    payload_digest          char(64),
    created_at              timestamptz not null default now(),

    primary key (investigation_result_id, tool_call_id)
);

create index ix_rca_tool_call_run on rca_tool_call(run_id, created_at);

-- ---------- 3. report_publication：发布状态机（M3-09 冻结；rca_report 不可变的配套） ----------
-- 一报告一发布记录； publication : notify_outbox = 1 : N（渠道维度展开在 outbox）

create table report_publication (
    id             uuid primary key,
    report_id      uuid not null unique references rca_report(id),

    state          varchar(16) not null,
    lease_owner    text,
    lease_until    timestamptz,
    lease_epoch    bigint not null default 0,
    attempt_count  integer not null default 0,
    max_attempts   integer not null default 5,
    available_at   timestamptz,
    last_error     jsonb,

    created_at     timestamptz not null,
    updated_at     timestamptz not null,

    constraint ck_publication_state
        check (state in ('PENDING','READY','SENT','RETRY_WAIT','DEAD','SUPPRESSED')),
    constraint ck_publication_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts)
);

create index ix_publication_claim on report_publication(available_at, created_at)
    where state in ('READY','RETRY_WAIT');

-- ---------- 4. notify_outbox：通知 outbox（§6.5 at-least-once） ----------
-- 唯一键 = (report_id, channel, template_version)——不靠随机 UUID 防重；
-- operation_id 进文案使重复可检测可审计；payload 只含白名单渲染字段（不含报告正文）

create table notify_outbox (
    id               uuid primary key,
    publication_id   uuid not null references report_publication(id),
    report_id        uuid not null references rca_report(id),
    channel          text not null,
    template_version text not null,
    operation_id     uuid not null,

    payload_json     jsonb not null,

    state            varchar(16) not null,
    lease_owner      text,
    lease_until      timestamptz,
    lease_epoch      bigint not null default 0,
    attempt_count    integer not null default 0,
    max_attempts     integer not null default 5,
    available_at     timestamptz not null default now(),
    last_error       jsonb,
    sent_at          timestamptz,

    created_at       timestamptz not null,
    updated_at       timestamptz not null,

    constraint uq_notify_outbox_delivery unique (report_id, channel, template_version),

    constraint ck_notify_outbox_state
        check (state in ('PENDING','CLAIMED','SENT','RETRY_WAIT','DEAD','SUPPRESSED')),
    constraint ck_notify_outbox_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
    constraint ck_notify_outbox_lifecycle
        check ((state = 'SENT') = (sent_at is not null))
);

-- 领取：公平排序（V3/V7 同原则）；单 worker 串行（§6.8 预算）
create index ix_notify_outbox_claim on notify_outbox(available_at, created_at)
    where state in ('PENDING','RETRY_WAIT');

-- 崩溃回收：租约过期的 CLAIMED 可被重领
create index ix_notify_outbox_lease on notify_outbox(lease_until)
    where state = 'CLAIMED';

-- ---------- 5. 授权（V7 同构：列级 UPDATE 只开口终态转换所需列） ----------

-- control_app：调查链路写入方（STARTED 先行 + 终态 CAS + 编排收尾）
grant select, insert on rca_investigation_result to control_app;
grant update (
    execution_status, validation_status, validation_errors, package_json,
    raw_artifact_ref, raw_digest, payload_digest, model, usage_json, finished_at
) on rca_investigation_result to control_app;
grant select, insert on rca_tool_call to control_app;
grant select, insert, update on report_publication to control_app;
grant select, insert on notify_outbox to control_app;

-- notify_app：只碰投递面（领取/退避/终态），不得改报告与调查记录（BA-10②/E2E-M3-07）
grant select on report_publication to notify_app;
grant update (
    state, lease_owner, lease_until, lease_epoch,
    attempt_count, available_at, last_error, updated_at
) on report_publication to notify_app;
grant select on notify_outbox to notify_app;
grant update (
    state, lease_owner, lease_until, lease_epoch,
    attempt_count, available_at, last_error, sent_at, updated_at
) on notify_outbox to notify_app;

-- eval_app：评测只读（评分数据源；eval_run 表的授权在 V10）
grant select on rca_investigation_result to eval_app;
grant select on rca_tool_call to eval_app;
grant select on report_publication to eval_app;
grant select on notify_outbox to eval_app;

-- 显式冻结（V2/V7 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on rca_investigation_result, rca_tool_call, report_publication, notify_outbox
    from publisher_app;
revoke all on rca_investigation_result, rca_tool_call, report_publication, notify_outbox
    from arena_app, chaos_admin_app;
revoke all on rca_investigation_result, rca_tool_call, report_publication, notify_outbox
    from public;
