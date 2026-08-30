-- ============================================================================
-- M0 V1 Schema — AI Code Review Agent
-- 权威依据：架构冻结文档 v2 数据设计第五章 + v2.2 修订第三部分 DDL 变更清单
-- M0 子集：不含 patch_proposal / approval（M5）、webhook_inbox（M1）
-- v2.2 变更已并入：
--   pr_subject  + publication_epoch（§3）、+ last_resolved_sequence（E2，评审修正 #5 改名）
--   review_run  + root_run_id（E9 双字段 lineage）
--   outbox_command 八态（无 CONFIRMED_STALE，§1）+ publication_epoch + fence_mode（§3）
--   outbox_dependency + dependency_mode（E3）
--   新表 publication_resource（§1）/ review_finding（§5）/ artifact（§5）
-- 应用生成 UUID，不绑定数据库 UUID 扩展。
-- ============================================================================

-- ---------- PR 与 Revision ----------

create table pr_subject (
    id                          uuid primary key,
    github_installation_id      bigint not null,
    github_repository_id        bigint not null,
    repository_full_name        text not null,
    pr_number                   integer not null,

    state                       varchar(16) not null,
    draft                       boolean not null default false,
    merged                      boolean not null default false,

    current_revision_id         uuid,
    current_policy_version      text not null,

    publication_epoch           bigint not null default 0,
    next_outbox_sequence        bigint not null default 1,
    last_resolved_sequence      bigint not null default 0,

    version                     bigint not null default 0,
    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,

    constraint uq_pr_subject
        unique (github_repository_id, pr_number),

    constraint ck_pr_subject_state
        check (state in ('OPEN', 'CLOSED'))
);

create table pr_revision (
    id                          uuid primary key,
    pr_subject_id               uuid not null references pr_subject(id),

    head_sha                    varchar(64) not null,
    base_ref                    text not null,
    base_sha                    varchar(64) not null,
    merge_base_sha              varchar(64),
    diff_digest                 char(64) not null,
    source_snapshot_digest      char(64),

    revision_fingerprint        char(64) not null,
    observed_at                 timestamptz not null,
    created_at                  timestamptz not null,

    constraint uq_pr_revision_fingerprint
        unique (pr_subject_id, revision_fingerprint)
);

alter table pr_subject
    add constraint fk_pr_subject_current_revision
    foreign key (current_revision_id)
    references pr_revision(id);

create index ix_pr_revision_subject_created
    on pr_revision(pr_subject_id, created_at desc);

-- ---------- ReviewRun ----------

create table review_run (
    id                          uuid primary key,
    pr_revision_id              uuid not null references pr_revision(id),
    parent_run_id               uuid references review_run(id),
    root_run_id                 uuid references review_run(id),

    run_key                     char(64) not null,
    trigger_key                 text not null,
    run_mode                    varchar(24) not null,

    policy_version              text not null,
    prompt_version              text not null,
    toolset_version             text not null,
    initial_model_route         text,

    state                       varchar(32) not null,
    publisher_disabled          boolean not null default false,

    token_budget                bigint,
    cost_budget_micros          bigint,
    deadline_at                 timestamptz,

    version                     bigint not null default 0,
    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,
    completed_at                timestamptz,

    constraint uq_review_run_key unique (run_key),

    constraint ck_review_run_mode
        check (run_mode in (
            'NORMAL',
            'PROJECTION_REBUILD',
            'RECORDED_REPLAY',
            'ISOLATED_REEXECUTION'
        )),

    constraint ck_review_run_state
        check (state in (
            'CREATED',
            'SNAPSHOTTING',
            'REVIEWING',
            'REVIEW_COMPLETE',
            'PATCH_PROPOSED',
            'WAITING_APPROVAL',
            'VERIFYING',
            'READY_TO_PUBLISH',
            'PUBLISHING',
            'COMPLETED',
            'COMPLETED_WITH_WARNINGS',
            'FAILED',
            'CANCELLED',
            'SUPERSEDED'
        )),

    constraint ck_replay_publisher_disabled
        check (
            run_mode = 'NORMAL'
            or publisher_disabled = true
        )
);

create index ix_review_run_revision
    on review_run(pr_revision_id, created_at desc);

create index ix_review_run_active
    on review_run(state, deadline_at)
    where state not in (
        'COMPLETED',
        'COMPLETED_WITH_WARNINGS',
        'FAILED',
        'CANCELLED',
        'SUPERSEDED'
    );

-- ---------- Step / WorkItem / Attempt ----------

create table run_step (
    id                          uuid primary key,
    review_run_id               uuid not null references review_run(id),
    parent_step_id              uuid references run_step(id),

    step_key                    text not null,
    operation_id                uuid not null,
    execution_scope             text not null default 'root',

    step_type                   varchar(32) not null,
    state                       varchar(24) not null,
    ordinal                     integer not null,

    input_artifact_digest       char(64),
    output_artifact_digest      char(64),

    max_attempts                integer not null default 3,
    timeout_seconds             integer not null,
    version                     bigint not null default 0,

    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,
    completed_at                timestamptz,

    constraint uq_step_key
        unique (review_run_id, step_key),

    constraint uq_step_operation
        unique (operation_id),

    constraint ck_step_state
        check (state in (
            'READY',
            'RUNNING',
            'WAITING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'SUPERSEDED'
        )),

    constraint ck_step_attempts
        check (max_attempts > 0),

    constraint ck_step_timeout
        check (timeout_seconds > 0)
);

create table work_item (
    id                          uuid primary key,
    review_run_id               uuid not null references review_run(id),
    step_id                     uuid not null references run_step(id),

    work_type                   varchar(32) not null,
    state                       varchar(24) not null,
    priority                    integer not null default 0,
    available_at                timestamptz not null,

    lease_owner                 text,
    lease_until                 timestamptz,
    lease_epoch                 bigint not null default 0,

    attempt_count               integer not null default 0,
    max_attempts                integer not null,

    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,

    constraint uq_work_item_step unique (step_id),

    constraint ck_work_item_state
        check (state in (
            'READY',
            'LEASED',
            'RETRY_WAIT',
            'DONE',
            'CANCELLED',
            'DEAD'
        )),

    constraint ck_work_attempt_count
        check (
            attempt_count >= 0
            and max_attempts > 0
            and attempt_count <= max_attempts
        )
);

create index ix_work_item_claim
    on work_item(priority desc, available_at, created_at)
    where state in ('READY', 'RETRY_WAIT');

create index ix_work_item_expired_lease
    on work_item(lease_until)
    where state = 'LEASED';

create table step_attempt (
    id                          uuid primary key,
    step_id                     uuid not null references run_step(id),
    work_item_id                uuid not null references work_item(id),

    attempt_no                  integer not null,
    lease_epoch                 bigint not null,
    worker_id                   text not null,

    status                      varchar(24) not null,
    actual_model_provider       text,
    actual_model                text,

    input_artifact_digest       char(64),
    output_artifact_digest      char(64),

    error_class                 varchar(32),
    error_code                  text,
    error_detail                jsonb,

    started_at                  timestamptz not null,
    finished_at                 timestamptz,

    constraint uq_step_attempt
        unique (step_id, attempt_no),

    constraint ck_attempt_status
        check (status in (
            'STARTED',
            'SUCCEEDED',
            'FAILED_RETRYABLE',
            'FAILED_TERMINAL',
            'ABANDONED',
            'STALE'
        ))
);

create index ix_attempt_step_started
    on step_attempt(step_id, started_at desc);

-- ---------- ExecutionEvent（只追加账本） ----------

create table execution_event (
    position                    bigint generated always as identity primary key,
    event_id                    uuid not null unique,

    review_run_id               uuid not null references review_run(id),
    pr_revision_id              uuid not null references pr_revision(id),
    step_id                     uuid references run_step(id),
    attempt_id                  uuid references step_attempt(id),

    event_type                  text not null,
    schema_version              integer not null,

    causation_event_id          uuid,
    correlation_id              uuid not null,
    producer                    text not null,

    payload                     jsonb not null,
    occurred_at                 timestamptz not null,
    recorded_at                 timestamptz not null default now(),

    constraint ck_event_schema_version
        check (schema_version > 0)
);

create index ix_event_run_position
    on execution_event(review_run_id, position);

create index ix_event_correlation
    on execution_event(correlation_id, position);

create index ix_event_step
    on execution_event(step_id, position)
    where step_id is not null;

-- ---------- Outbox（八态，v2.2 §1/§3） ----------

create table outbox_command (
    operation_id                uuid primary key,

    pr_subject_id               uuid not null references pr_subject(id),
    review_run_id               uuid not null references review_run(id),
    pr_revision_id              uuid not null references pr_revision(id),

    aggregate_key               text not null,
    aggregate_sequence          bigint not null,
    publication_epoch           bigint not null,
    fence_mode                  varchar(24) not null default 'CURRENT_EPOCH',

    command_type                varchar(32) not null,
    state                       varchar(24) not null,

    policy_version              text not null,
    payload_artifact_digest     char(64),
    payload_hash                char(64) not null,
    remote_identity_type        varchar(32) not null,

    lease_owner                 text,
    lease_until                 timestamptz,
    lease_epoch                 bigint not null default 0,

    attempt_count               integer not null default 0,
    max_attempts                integer not null default 3,
    next_attempt_at             timestamptz,

    remote_id                   text,
    remote_url                  text,

    reconcile_not_found_count   integer not null default 0,
    reconcile_after             timestamptz,

    last_error_code             text,
    last_error_detail           jsonb,

    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,
    confirmed_at                timestamptz,

    constraint uq_outbox_aggregate_sequence
        unique (aggregate_key, aggregate_sequence),

    constraint ck_outbox_state
        check (state in (
            'PENDING',
            'IN_FLIGHT',
            'RECONCILING',
            'RETRY_WAIT',
            'CONFIRMED',
            'SUPERSEDED',
            'FAILED_TERMINAL',
            'MANUAL'
        )),

    -- M0 命令白名单（B15 类型化命令；CREATE_FIX_BRANCH/CREATE_CHILD_PR 属 M5，
    -- 届时由新迁移扩展本 CHECK 并补 patch/approval/verification 引用列）
    constraint ck_outbox_command_type
        check (command_type in (
            'CREATE_CHECK',
            'UPDATE_CHECK',
            'PUBLISH_REVIEW'
        )),

    constraint ck_outbox_fence_mode
        check (fence_mode in (
            'CURRENT_EPOCH',
            'OWNED_GENERATION'
        )),

    constraint ck_outbox_attempts
        check (
            attempt_count >= 0
            and max_attempts > 0
            and attempt_count <= max_attempts
        )
);

create index ix_outbox_ready
    on outbox_command(aggregate_key, aggregate_sequence)
    where state in ('PENDING', 'RETRY_WAIT');

create index ix_outbox_expired_lease
    on outbox_command(lease_until)
    where state in ('IN_FLIGHT', 'RECONCILING');

create index ix_outbox_reconcile
    on outbox_command(reconcile_after)
    where state = 'RECONCILING';

create index ix_outbox_run
    on outbox_command(review_run_id, created_at);

create table outbox_dependency (
    operation_id                uuid not null
        references outbox_command(operation_id),

    depends_on_operation_id     uuid not null
        references outbox_command(operation_id),

    dependency_mode             varchar(24) not null default 'REQUIRE_CONFIRMED',

    created_at                  timestamptz not null,

    primary key (
        operation_id,
        depends_on_operation_id
    ),

    constraint ck_no_self_dependency
        check (operation_id <> depends_on_operation_id),

    constraint ck_dependency_mode
        check (dependency_mode in (
            'REQUIRE_CONFIRMED',
            'REQUIRE_TERMINAL',
            'OPTIONAL'
        ))
);

create index ix_outbox_dependency_reverse
    on outbox_dependency(depends_on_operation_id);

-- ---------- PublicationResource（v2.2 §1，漂移资源视角） ----------

create table publication_resource (
    id                          uuid primary key,
    resource_type               varchar(32) not null,
    created_by_operation_id     uuid not null references outbox_command(operation_id),
    pr_subject_id               uuid not null references pr_subject(id),
    remote_id                   text not null,
    remote_url                  text,
    marker                      text,
    state                       varchar(24) not null default 'ACTIVE',
    drift_detected_at           timestamptz,
    repaired_by_operation_id    uuid references outbox_command(operation_id),
    created_at                  timestamptz not null,
    updated_at                  timestamptz not null,

    constraint ck_pub_resource_type
        check (resource_type in (
            'CHECK_RUN', 'PR_COMMENT', 'REVIEW', 'REVIEW_COMMENT',
            'FIX_BRANCH', 'CHILD_PR'
        )),

    constraint ck_pub_resource_state
        check (state in ('ACTIVE', 'DRIFTED', 'REPAIRED', 'RETIRED')),

    constraint uq_pub_resource unique (resource_type, remote_id)
);

-- ---------- ReviewFinding 与 Artifact（v2.2 §5） ----------

create table review_finding (
    id                          uuid primary key,
    review_run_id               uuid not null references review_run(id),
    pr_revision_id              uuid not null references pr_revision(id),

    fingerprint                 char(64) not null,
    rule_id                     text not null,
    severity                    varchar(16) not null,
    file_path                   text not null,
    line_start                  integer,
    line_end                    integer,
    body_artifact_digest        char(64),

    state                       varchar(24) not null default 'PENDING',
    created_at                  timestamptz not null,

    constraint uq_finding_per_run unique (review_run_id, fingerprint),

    constraint ck_finding_state
        check (state in ('PENDING', 'PUBLISHED', 'SUPERSEDED', 'WAIVED'))
);

create index ix_finding_run
    on review_finding(review_run_id, created_at);

create table artifact (
    digest                      char(64) primary key,
    artifact_type               varchar(32) not null,
    size_bytes                  bigint not null,
    storage_path                text not null,
    created_at                  timestamptz not null,

    constraint ck_artifact_type
        check (artifact_type in (
            'SOURCE_SNAPSHOT',
            'DIFF_BUNDLE',
            'FINDING_BODY',
            'REVIEW_PAYLOAD',
            'WEBHOOK_PAYLOAD',
            'MODEL_RESPONSE'
        ))
);

-- ---------- 不可变表保护（第二道保险；角色权限为第一道） ----------

create or replace function reject_immutable_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'table % is append-only/immutable', tg_table_name;
end;
$$;

create trigger trg_pr_revision_immutable
before update or delete on pr_revision
for each row execute function reject_immutable_mutation();

create trigger trg_execution_event_immutable
before update or delete on execution_event
for each row execute function reject_immutable_mutation();
