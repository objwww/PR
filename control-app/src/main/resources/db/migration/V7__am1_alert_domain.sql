-- ============================================================================
-- V7 —— AM1 告警域（docs/告警AM1-技术方案.md v2.1 §6.1，9 表）
--   alert_inbox            AM webhook.go Message 全字段 + 六态/租约/退避 + decision（V3 同构）
--   alert_event            不可变追加：双哈希分离（payload_hash/investigation_hash）+ episode generation
--   incident               incident_key 唯一（不含 severity）+ 三计数分离 + episode 水印 + rerun 待查哈希
--   rca_run                三级化第一层；唯一活跃约束（部分唯一索引，评审 #1）
--   rca_task               调度列齐全（SLA 晋升排序 §6.2：deadline_at = ready_since + sla(priority)）
--   rca_attempt            六态终态记录（V1 step_attempt 同构）
--   rca_report             package jsonb 六段式 + validation_status 结构验证链落点
--   external_invocation_ledger  Holmes 调用账本（V5 model_call_ledger 形态，只增不改+列级 UPDATE）
--   scheduler_slot         固定槽位租约表（评审 #6，替代会泄漏的 running 计数器）
--   授权：control_app CRUD；alert_event/ledger 只 INSERT+SELECT（INV-AM1-5）；publisher_app 显式 REVOKE
-- ============================================================================

-- ---------- 1. alert_inbox：整组原子落库的收件箱 ----------

create table alert_inbox (
    id                  uuid primary key,
    -- group envelope（Alertmanager webhook.go Message 全字段；EX-A03 尺寸门在入口先行拦截）
    version             text not null,
    receiver            text not null,
    group_key           text not null,
    group_labels        jsonb not null,
    common_labels       jsonb not null,
    common_annotations  jsonb not null,
    external_url        text,
    group_status        text not null,
    truncated_alerts    integer not null default 0,
    alert_count         integer not null default 0,
    payload_raw         bytea not null,    -- 审计唯一权威（含 HMAC 复核预留）
    payload_digest      char(64) not null, -- sha256(payload_raw)，重投比对用

    -- 处理机（V3 webhook_inbox 同构：六态 + 租约 + 退避）
    state               varchar(24) not null,
    decision            varchar(16),       -- 投影期填写（§6.4 软背压：DEFERRED 行本身即审计）
    lease_owner         text,
    lease_until         timestamptz,
    lease_epoch         bigint not null default 0,
    attempt_count       integer not null default 0,
    max_attempts        integer not null default 5,
    next_retry_at       timestamptz,
    last_error          jsonb,

    received_at         timestamptz not null,
    updated_at          timestamptz not null,
    processed_at         timestamptz,

    constraint ck_alert_inbox_state
        check (state in ('RECEIVED','PROCESSING','RETRY_WAIT','PROCESSED','IGNORED','DEAD_LETTER')),

    constraint ck_alert_inbox_decision
        check (decision is null or decision in ('ACCEPTED','DEFERRED','SUPPRESSED')),

    constraint ck_alert_inbox_group_status
        check (group_status in ('firing','resolved')),

    constraint ck_alert_inbox_truncated
        check (truncated_alerts >= 0 and alert_count >= 0),

    constraint ck_alert_inbox_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts)
);

-- 领取：公平排序（V3 同原则，防 LIMIT 饿死尾部）
create index ix_alert_inbox_claim on alert_inbox(next_retry_at, received_at)
    where state in ('RECEIVED','RETRY_WAIT');

-- 崩溃回收：租约过期的 PROCESSING 可被重领
create index ix_alert_inbox_lease on alert_inbox(lease_until)
    where state = 'PROCESSING';

-- ---------- 2. incident：告警事实聚合（先建，alert_event/rca_run 依赖它） ----------

create table incident (
    id                         uuid primary key,
    incident_key               text not null unique,   -- alertname+service 等稳定标签；不含告警级别（INV-AM1-4，升级不换单）
    status                     varchar(16) not null,
    generation                 integer not null default 0,  -- resolved 后再 firing = 同身份新 episode，generation+1

    -- episode 水印（§6.7 乱序策略：晚到 resolved（starts_at < 水印）不覆盖更新的 firing）
    episode_started_at         timestamptz not null,
    last_firing_starts_at      timestamptz,
    resolved_at                timestamptz,

    -- 双哈希的 incident 侧落点（rerun 判定，§6.7 finishTask 算法）
    last_investigation_hash    char(64),
    pending_investigation_hash char(64),               -- 调查中收到的材料变化，收尾时判 rerun

    -- 三计数分离（评审 #2：AM 通知条数 / 去重事件条数 / 原始接收条数）
    received_count             bigint not null default 0,
    distinct_event_count       bigint not null default 0,
    notification_count         bigint not null default 0,

    current_rca_run_id         uuid,                   -- FK 在 rca_run 建表后补（循环依赖）

    first_seen_at              timestamptz not null,
    last_event_at              timestamptz not null,
    created_at                 timestamptz not null,
    updated_at                 timestamptz not null,

    constraint ck_incident_status
        check (status in ('FIRING','RESOLVED')),
    constraint ck_incident_generation
        check (generation >= 0),
    constraint ck_incident_counts
        check (received_count >= 0 and distinct_event_count >= 0 and notification_count >= 0)
);

-- ---------- 3. alert_event：不可变追加（双哈希分离，评审修正） ----------

create table alert_event (
    id                  uuid primary key,
    inbox_id            uuid not null references alert_inbox(id),
    incident_id         uuid not null references incident(id),
    generation          integer not null,     -- 归属 incident.generation 快照

    fingerprint         text not null,        -- 上游身份（AM 指纹）
    status              text not null,        -- AM 原文小写
    labels              jsonb not null,
    annotations         jsonb not null,
    starts_at           timestamptz not null,
    ends_at             timestamptz,

    payload_hash        char(64) not null,    -- sha256(规范化(labels+status+startsAt))：判"同一条是否处理过"
    investigation_hash  char(64) not null,    -- sha256(材料性子集)：判"值不值得重查"；不含动态数值 annotations

    recorded_at         timestamptz not null default now(),

    constraint ck_alert_event_status
        check (status in ('firing','resolved')),
    constraint ck_alert_event_generation
        check (generation >= 0),

    -- 投影层幂等锚点（§6.3：payload_hash 相同 → 仅 received_count+1）
    constraint uq_alert_event_dedup
        unique (fingerprint, payload_hash, starts_at)
);

create index ix_alert_event_incident on alert_event(incident_id, recorded_at);
create index ix_alert_event_fingerprint on alert_event(fingerprint, starts_at desc);

-- ---------- 4. rca_run：三级化第一层，唯一活跃约束在 run 层 ----------

create table rca_run (
    id             uuid primary key,
    incident_id    uuid not null references incident(id),
    generation     integer not null,           -- 铸造时的 incident.generation 快照（episode 代；rerun 同代，§6.7）
    trigger_kind   varchar(16) not null default 'INITIAL',
    state          varchar(24) not null,
    investigation_hash char(64) not null,      -- 铸造时材料快照；finishTask 与 incident.pending 比较判 rerun（ST-A05）

    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    started_at     timestamptz,
    finished_at    timestamptz,
    last_error     jsonb,

    constraint ck_rca_run_state
        check (state in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED')),
    constraint ck_rca_run_generation
        check (generation >= 0),
    constraint ck_rca_run_trigger
        check (trigger_kind in ('INITIAL','RERUN')),
    constraint ck_rca_run_finish
        check (state in ('QUEUED','RUNNING') or finished_at is not null)
);

-- INV-AM1-2：同一 Incident 最多一个活跃 rca_run（DB 强制，CT-A03 并发 23505 实证）
create unique index uq_rca_run_active_incident
    on rca_run(incident_id) where state in ('QUEUED','RUNNING');

create index ix_rca_run_incident on rca_run(incident_id, created_at desc);

-- incident.current_rca_run_id 循环 FK 补挂
alter table incident
    add constraint fk_incident_current_run
    foreign key (current_rca_run_id) references rca_run(id);

-- ---------- 5. rca_task：调度列齐全（V1 work_item 形态 + SLA 晋升列） ----------

create table rca_task (
    id             uuid primary key,
    run_id         uuid not null references rca_run(id),
    task_key       text not null,              -- 本期唯一值 'HOLMES_INVESTIGATE'
    state          varchar(24) not null,
    priority       integer not null default 0, -- critical=200/warning=100/info=0（§6.2）

    available_at   timestamptz not null,       -- 退避结束可领取时刻
    ready_since    timestamptz not null,       -- SLA 起算点（重试置 READY 时刷新，§6.2）
    deadline_at    timestamptz not null,       -- ready_since + sla(priority)；critical 用 'infinity'（永不到期）

    lease_owner    text,
    lease_until    timestamptz,
    lease_epoch    bigint not null default 0,

    attempt_count  integer not null default 0,
    max_attempts   integer not null default 3,

    created_at     timestamptz not null,
    updated_at     timestamptz not null,

    constraint uq_rca_task_key unique (run_id, task_key),

    constraint ck_rca_task_state
        check (state in ('READY','LEASED','RETRY_WAIT','DONE','CANCELLED','DEAD')),

    constraint ck_rca_task_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts)
);

-- 领取排序 §6.2：(now() >= deadline_at) DESC, priority DESC, deadline_at, created_at, id
create index ix_rca_task_claim on rca_task(priority desc, deadline_at, created_at)
    where state in ('READY','RETRY_WAIT');

-- 崩溃回收：租约过期的 LEASED 可被重领
create index ix_rca_task_lease on rca_task(lease_until)
    where state = 'LEASED';

-- ---------- 6. rca_attempt：六态终态记录（V1 step_attempt 同构） ----------

create table rca_attempt (
    id             uuid primary key,
    task_id        uuid not null references rca_task(id),
    attempt_no     integer not null,
    lease_epoch    bigint not null,
    worker_id      text not null,

    status         varchar(24) not null,
    error_class    varchar(32),
    error_code     text,
    error_detail   jsonb,

    started_at     timestamptz not null,
    finished_at    timestamptz,

    constraint uq_rca_attempt unique (task_id, attempt_no),

    constraint ck_rca_attempt_status
        check (status in ('STARTED','SUCCEEDED','FAILED_RETRYABLE','FAILED_TERMINAL','ABANDONED','STALE'))
);

create index ix_rca_attempt_task on rca_attempt(task_id, started_at desc);

-- ---------- 7. rca_report：结构验证链落点 ----------

create table rca_report (
    id                 uuid primary key,
    run_id             uuid not null references rca_run(id),
    attempt_id         uuid not null references rca_attempt(id),
    schema_version     integer not null,
    validation_status  varchar(32) not null,
    validation_errors  jsonb,                 -- REJECTED_* 时记录拒绝原因链
    package_json       jsonb not null,        -- 六段式结构化报告
    raw_text           text not null,         -- Holmes 原文（脱敏后）
    model              text,
    prompt_tokens      integer,
    completion_tokens  integer,
    total_tokens       integer,
    usage_missing      boolean not null default true,
    created_at         timestamptz not null,

    constraint ck_rca_report_status
        check (validation_status in ('STRUCTURE_VALIDATED',
                                     'REJECTED_MALFORMED','REJECTED_OVERSIZE',
                                     'REJECTED_SCHEMA_VERSION','REJECTED_SCHEMA_MISMATCH')),
    constraint ck_rca_report_schema_version
        check (schema_version > 0),
    constraint ck_rca_report_tokens
        check (prompt_tokens is null or prompt_tokens >= 0)
);

create index ix_rca_report_run on rca_report(run_id, created_at desc);

-- ---------- 8. external_invocation_ledger：Holmes 调用账本（V5 形态） ----------

create table external_invocation_ledger (
    id                uuid primary key,
    invocation_id     uuid not null,
    call_seq          int  not null check (call_seq >= 1),
    run_id            uuid not null references rca_run(id),
    task_id           uuid not null references rca_task(id),
    attempt_id        uuid not null references rca_attempt(id),
    lease_epoch       bigint not null,

    endpoint          text not null,          -- /api/chat 等
    request_digest    char(64) not null,      -- 请求体摘要（不含密钥）
    response_digest   char(64),

    state             text not null check (state in ('STARTED','SUCCEEDED','FAILED','UNKNOWN')),
    http_status       int  check (http_status between 100 and 599),
    latency_ms        bigint check (latency_ms >= 0),

    prompt_tokens     int,
    completion_tokens int,
    total_tokens      int,
    usage_missing     boolean not null default false,

    holmes_version    text,                   -- T08 pin 的版本快照
    model             text,
    toolset_version   text,
    error_class       text,
    sanitized_message text,

    started_at        timestamptz not null default now(),
    finished_at       timestamptz,

    -- 状态机一致性（V5 同构）：STARTED 未完成；终态必有 finished_at
    check ((state = 'STARTED'   and response_digest is null and finished_at is null)
        or (state in ('SUCCEEDED','FAILED','UNKNOWN') and finished_at is not null)),

    unique (invocation_id, call_seq)
);

-- 崩溃回收：悬挂 STARTED → UNKNOWN 扫描入口（CT-A08）
create index ix_xinv_started on external_invocation_ledger(started_at)
    where state = 'STARTED';

create index ix_xinv_run on external_invocation_ledger(run_id, started_at);

-- ---------- 9. scheduler_slot：固定槽位租约表（评审 #6） ----------

create table scheduler_slot (
    scope        text not null,
    slot_no      integer not null check (slot_no >= 1),
    lease_owner  text,
    lease_until  timestamptz,
    lease_epoch  bigint not null default 0,
    task_id      uuid references rca_task(id),   -- 持槽任务（双回收审计）
    updated_at   timestamptz not null default now(),
    primary key (scope, slot_no)
);

-- 崩溃回收：租约过期的占用槽可被重领
create index ix_slot_lease on scheduler_slot(lease_until)
    where lease_owner is not null;

-- 固定槽位预置（AM1 默认 2 并发，与 app.alert.worker.slots 默认值对齐；扩容 = 新迁移加行，不 DELETE）
insert into scheduler_slot(scope, slot_no) values ('rca', 1), ('rca', 2);

-- ---------- 10. 授权（V3/V5 同构） ----------

grant select, insert, update on alert_inbox to control_app;
grant select, insert on alert_event to control_app;            -- 只增不改（INV-AM1-5）
grant select, insert, update on incident to control_app;
grant select, insert, update on rca_run to control_app;
grant select, insert, update on rca_task to control_app;
grant select, insert, update on rca_attempt to control_app;
grant select, insert on rca_report to control_app;
grant select, insert on external_invocation_ledger to control_app;

-- 账本列级 UPDATE：仅允许终态转换相关列（V5 惯例）
grant update (
    state, response_digest, http_status, latency_ms,
    prompt_tokens, completion_tokens, total_tokens, usage_missing,
    holmes_version, model, toolset_version,
    error_class, sanitized_message, finished_at
) on external_invocation_ledger to control_app;

-- 固定槽位表：只允许租约翻转，不允许增删行
grant select, update on scheduler_slot to control_app;

-- publisher_app 显式冻结（V2 惯例：防未来 grant all 漂移）；PUBLIC 零权限
revoke all on alert_inbox, alert_event, incident, rca_run, rca_task,
    rca_attempt, rca_report, external_invocation_ledger, scheduler_slot
    from publisher_app;
revoke all on alert_inbox, alert_event, incident, rca_run, rca_task,
    rca_attempt, rca_report, external_invocation_ledger, scheduler_slot
    from public;
