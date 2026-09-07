-- ============================================================================
-- V26 —— AM5 M5-11 OperatorCase 处置单（ops 域）
--   operator_case   人工处置单（(tenant,fingerprint) 幂等合并键；快照冻结——
--                   snapshot_digest/observed_generation 固定创建时值；activities/
--                   audits 内嵌 jsonb 投影（P4 mock 形状），audits[].key 兼作命令
--                   幂等重放锚；resolution 结构化结案原因）
--   notify_outbox   +case_id 关联列（SLA 升级/分配通知的 AM7 IN_APP 渠道预留缝，
--                   本迁移只落关联列不写通知行——落码方案 §M5-11① 原文）
--
-- 编号说明：落码方案 §M5-11① 新增号段（方案原 V23 顺延）。
-- 状态全集：OPEN/ACKED/RESOLVED（与前端 mocks/cases.js status 值核对；RESOLVED
-- 为吸收态，仅 MERGE 复发聚合可落其上——状态机矩阵唯一权威）。
-- ============================================================================

-- ---------- 1. operator_case ----------

create table operator_case (
    id                  uuid primary key,
    tenant              varchar(64) not null,
    fingerprint         varchar(64) not null,
    subject             text not null,
    priority            varchar(4) not null check (priority in ('P0','P1','P2')),
    reason_code         varchar(32) not null,
    status              varchar(16) not null default 'OPEN'
                        check (status in ('OPEN','ACKED','RESOLVED')),
    owner               varchar(64),

    -- 来源引用（可空：预算/通知侧来源无 Run 绑定）
    run_id              uuid,
    task_id             varchar(128),
    incident_type       varchar(128),

    -- 快照冻结（P4 annot：迟到证据进入新快照，不改写旧裁决上下文）
    snapshot_digest     char(64),
    observed_generation integer not null default 0,

    -- 1..N 可核验来源引用（N≥1 由 check 兜底）
    evidence_refs       jsonb not null default '[]'::jsonb
                        check (jsonb_typeof(evidence_refs) = 'array'
                               and jsonb_array_length(evidence_refs) >= 1),

    -- 内嵌投影（C-16①：P4 mock activities[]/audits[] 形状）
    activities          jsonb not null default '[]'::jsonb
                        check (jsonb_typeof(activities) = 'array'),
    audits              jsonb not null default '[]'::jsonb
                        check (jsonb_typeof(audits) = 'array'),

    -- 结构化结案原因（{code,note,at}；resolve 必填）
    resolution          jsonb,

    first_seen          timestamptz not null default now(),
    ack_due             timestamptz,
    resolve_due         timestamptz,

    revision            bigint not null default 1,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),

    constraint uq_operator_case_tenant_fingerprint unique (tenant, fingerprint)
);

comment on table operator_case is
    'AM5 M5-11 人工处置单（幂等合并键 (tenant,fingerprint)——再发生 revision+1 不新建单；快照冻结；结案不回写 Run 结论）';

create index ix_operator_case_status on operator_case(status, resolve_due)
    where status in ('OPEN','ACKED');

-- ---------- 2. notify_outbox 关联列（AM7 IN_APP 渠道预留缝） ----------

alter table notify_outbox add column case_id uuid references operator_case(id);

comment on column notify_outbox.case_id is
    'AM5 M5-11 关联处置单（SLA 升级/分配通知；写入面 = AM7 IN_APP 渠道配套，M5-11 只落列）';

-- ---------- 3. 授权（业务表：select,insert,update——命令面 CAS 必需；delete 零开口） ----------

grant select, insert, update on operator_case to control_app;
revoke delete on operator_case from control_app;

revoke all on operator_case
    from publisher_app, notify_app, eval_app, public;
