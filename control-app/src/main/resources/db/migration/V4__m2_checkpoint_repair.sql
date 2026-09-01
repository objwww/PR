-- ============================================================================
-- V4 —— M2 可恢复执行与漂移修复（docs/M2-技术方案.md v1.1 §4.1）
-- ============================================================================

-- ---------- Step 内 checkpoint：唯一键 + 契约摘要 + 租约世代栅栏 ----------

create table step_checkpoint (
    id                           uuid primary key,
    step_id                      uuid not null references run_step(id),
    checkpoint_key               text not null,
    output_artifact_digest       char(64) not null,
    model_response_digest        char(64) not null,
    checkpoint_contract_digest   char(64) not null,
    prompt_template_version      text not null,
    finding_schema_version       text not null,
    mapper_contract_version      text not null,
    context_builder_version      text not null,
    model_identity               text not null,
    lease_epoch                  bigint not null,
    attempt_no                   integer not null,
    created_at                   timestamptz not null default now(),

    constraint uq_step_checkpoint unique (step_id, checkpoint_key),
    constraint ck_step_checkpoint_attempt check (attempt_no > 0),
    constraint ck_step_checkpoint_lease_epoch check (lease_epoch >= 0)
);

-- ---------- 漂移修复单：七态、人工审批审计、重试预算 ----------

create table repair_request (
    id                           uuid primary key,
    publication_resource_id      uuid not null references publication_resource(id),
    resource_type                varchar(32) not null,
    policy_tier                  varchar(8) not null,
    state                        varchar(16) not null,
    repair_run_id                uuid references review_run(id),
    repair_operation_id          uuid,
    approved_by                  text,
    approved_at                  timestamptz,
    approval_reason              text,
    attempt_count                integer not null default 0,
    max_attempts                 integer not null default 5,
    next_attempt_at              timestamptz,
    last_error                   text,
    created_at                   timestamptz not null default now(),
    updated_at                   timestamptz not null default now(),

    constraint ck_repair_tier check (policy_tier in ('AUTO', 'MANUAL')),
    constraint ck_repair_state check (state in (
        'PENDING', 'APPROVED', 'DISPATCHED', 'RETRY_WAIT',
        'REPAIRED', 'FAILED_TERMINAL', 'EXPIRED'
    )),
    constraint ck_repair_approval check (
        state <> 'APPROVED'
        or (approved_by is not null and approved_at is not null and approval_reason is not null)
    ),
    constraint ck_repair_attempts check (
        attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts
    )
);

create unique index uq_repair_active
    on repair_request(publication_resource_id)
    where state in ('PENDING', 'APPROVED', 'DISPATCHED', 'RETRY_WAIT');

create index ix_repair_fair_scan
    on repair_request(created_at)
    where state in ('PENDING', 'APPROVED', 'RETRY_WAIT');

-- policy_tier 是铸单时的策略事实，后续只能推进 state，不能改档。
create or replace function reject_repair_tier_mutation()
returns trigger
language plpgsql
as $$
begin
    if new.policy_tier is distinct from old.policy_tier then
        raise exception 'repair_request policy_tier is immutable';
    end if;
    return new;
end;
$$;

create trigger trg_repair_tier_immutable
before update on repair_request
for each row execute function reject_repair_tier_mutation();

-- RM2-02：铸单只能以 PENDING 出生且审批审计列恒空；APPROVED 只能由 control 经
-- 人工批准 UPDATE 抵达（R2：REVIEW 恒人工门，publisher 不得伪造审批）。
create or replace function enforce_repair_insert_pending()
returns trigger
language plpgsql
as $$
begin
    if new.state <> 'PENDING' or new.approved_by is not null
       or new.approved_at is not null or new.approval_reason is not null then
        raise exception 'repair_request 只能以 PENDING 且无审批审计列铸造';
    end if;
    return new;
end;
$$;

create trigger trg_repair_insert_pending
before insert on repair_request
for each row execute function enforce_repair_insert_pending();

-- ---------- 资源身份链与内容漂移 episode ----------

alter table publication_resource
    add column replaces_resource_id uuid references publication_resource(id),
    add column content_drift_detected_at timestamptz,
    add column content_drift_digest char(64);

-- ---------- REPAIR Run ----------

alter table review_run drop constraint ck_review_run_mode;
alter table review_run add constraint ck_review_run_mode
    check (run_mode in (
        'NORMAL', 'PROJECTION_REBUILD', 'RECORDED_REPLAY',
        'ISOLATED_REEXECUTION', 'REPAIR'
    ));

-- RM2-10：回放/重建类 Run 禁止发布的边界不变；REPAIR Run 的存在意义就是铸出并发布
-- repair 命令，允许 publisher_disabled=false。
alter table review_run drop constraint ck_replay_publisher_disabled;
alter table review_run add constraint ck_replay_publisher_disabled
    check (run_mode in ('NORMAL', 'REPAIR') or publisher_disabled = true);

-- ---------- 最小权限矩阵 ----------

grant all privileges on step_checkpoint to control_app;
revoke all privileges on step_checkpoint from publisher_app;

grant select, update on repair_request to control_app;
-- RM2-02：publisher 只能列级 INSERT 业务列（与 DriftReconciler 铸单 INSERT 列清单一致，
-- 含重试预算/错误列）；审批三列与 repair_run_id/repair_operation_id 不可写。
grant select on repair_request to publisher_app;
grant insert (id, publication_resource_id, resource_type, policy_tier, state,
              attempt_count, max_attempts, next_attempt_at, last_error,
              created_at, updated_at)
    on repair_request to publisher_app;
grant update (state, repair_run_id, repair_operation_id, attempt_count,
              next_attempt_at, last_error, updated_at)
    on repair_request to publisher_app;

-- V3 已把 publication_resource 收窄为列级 UPDATE；M2 只补修复收尾和内容巡检所需列。
grant update (state, drift_detected_at, repaired_by_operation_id,
              last_checked_at, next_check_at, check_error_count,
              content_drift_detected_at, content_drift_digest, updated_at)
    on publication_resource to publisher_app;

-- 显式重申两条安全边界，防未来授权漂移。
revoke insert, delete on outbox_command from publisher_app;
revoke update, delete on outbox_command from control_app;
