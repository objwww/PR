-- ============================================================================
-- V87 —— EV-08 数据集与人工评审：评审任务与评审结果（docs/告警-评测中心与能力
--   版本演进-审查及详细改造方案-v1.md EV-08 卡 / §3.6 / §5.3 "GET/POST
--   /api/eval/reviews/... 队列、领取、草稿、CAS提交——独立评审记录与rubric
--   版本，不覆盖原机器评分"）
--   review_assignment  评审任务：run/case 引用 + 评审人 actor + 状态机
--                      （PENDING 待领取 / IN_PROGRESS 进行中 / SUBMITTED 已提交）
--                      + 有界租约（lease_expires_at 超时惰性回收——读面投影与
--                      领取 CAS 谓词双路判定，无 worker）+ revision CAS 锚
--   review_verdict     评审结果（insert-only）：评分/标签/理由 + 冻结 rubric
--                      版本 + 提交时刻；uq(assignment_id) = 一任务一结论，
--                      重评分 = 新任务 + 新行（旧行永不覆盖，审计闭环）
--
-- 号段纪律：V80~V83、V85、V86 已占用，V84 空置（EV-05 零迁移声明），EV-08 自 V87 起。
--
-- 语义纪律（V85 eval_comparison / EvaluationRecordV1 同律）：
--   - review_verdict insert-only：落档即冻结，无任何 UPDATE/DELETE 授权；更正与
--     重评分 = 新 review_assignment 领取后提交新行，旧行保留（§3.6"机器分、人工分、
--     两者分歧分别保留；人工也会误判，支持复核裁决"）；
--   - 理由必填（§3.6"必填理由与证据引用"）：reason 去空白后非空 CHECK 兜底；
--   - "无法判定/来源不足"是一等结论（§3.6 不要求审核者强行二选一）：
--     verdict 封闭值域含 UNDECIDABLE / INSUFFICIENT_SOURCE；
--   - 租约回收惰性化（EV-08 卡"选简单可靠的"）：IN_PROGRESS + lease_expires_at
--     已过 = 读面投影 PENDING、领取 CAS 谓词可重领，无后台 worker 无定时任务。
--
-- 授权分工（V45/V85/V86 同律）：
--   - control_app（/api/eval 面 HTTP 身份）：review_assignment select,insert +
--     列级 update（仅状态推进六列：reviewer/status/revision/claimed_at/
--     lease_expires_at/submitted_at——run_id/case_execution_id/created_by 落档
--     后不可改，V86 drill_job 列级授权同构）；review_verdict select,insert
--     （insert-only，零 update/delete 开口）；
--   - eval_app/publisher/notify/arena/chaos/public：显式 revoke 归零（评审是
--     control 面人工工作区，eval-runner 与生产角色无消费面）。
--
-- case_version_partition_counts()：HOLDOUT 计数针孔。case_version 有 RLS
-- （V21/V45），control_app 不可见 HOLDOUT 行——连计数都拿不到。数据集读面需要
-- "HOLDOUT 分区只出计数与元数据"（EV-08 卡，GT 原文永不进投影），故以
-- security definer 函数开一个只出 (dataset_version_id, partition_class, count)
-- 三列聚合计数的显式针孔：不出任何行内容，search_path 钉死防劫持。
-- ============================================================================

-- ---------- 1. review_assignment：评审任务（状态机 + 租约 + revision CAS 锚） ----------

create table review_assignment (
    id                 uuid primary key,
    run_id             uuid not null references eval_run(id),
    case_execution_id  uuid not null references eval_case_result(id),

    status             varchar(16) not null default 'PENDING'
                       check (status in ('PENDING', 'IN_PROGRESS', 'SUBMITTED')),
    reviewer           varchar(64),              -- 领取前 NULL；actor 唯一来源 = 认证主体
    revision           integer not null default 0, -- CAS 锚：领取/提交/回收重领各 +1

    claimed_at         timestamptz,
    lease_expires_at   timestamptz,              -- 有界租约；超时惰性回收（无 worker）
    submitted_at       timestamptz,

    created_by         varchar(64) not null,     -- 任务生成主体（认证面唯一来源）
    created_at         timestamptz not null default now(),

    -- 状态-字段形状双向钉死（V86 drill_job 同律）：进行中必带评审人与租约，
    -- 已提交必带提交时刻，待领取零评审面
    constraint ck_review_assignment_shape check (
        (status = 'PENDING' and reviewer is null and claimed_at is null
            and lease_expires_at is null and submitted_at is null)
        or (status = 'IN_PROGRESS' and reviewer is not null and claimed_at is not null
            and lease_expires_at is not null and submitted_at is null)
        or (status = 'SUBMITTED' and reviewer is not null and submitted_at is not null)),
    constraint ck_review_assignment_lease check (
        lease_expires_at is null or claimed_at is null or lease_expires_at > claimed_at),
    -- 复合唯一：review_verdict 三列冗余直挂的 FK 目标（V21 冗余列复合 FK 同构）
    constraint uq_review_assignment_id_run_case unique (id, run_id, case_execution_id)
);

comment on table review_assignment is
    'EV-08 评审任务：PENDING/IN_PROGRESS/SUBMITTED 三态 + 有界租约（超时惰性回收）'
    || ' + revision CAS 锚（EU29 双人同领/同提交显式冲突）';
comment on column review_assignment.revision is
    'CAS 锚：每次状态推进 +1；提交携带 expectedRevision 不符 = 409（租约被回收重领后旧持有者提交必撞）';

-- 同一评审人对同一案例至多持有一条进行中任务（双份评派给不同人，同人重复领取
-- 由 uq 违约兜底；PENDING（reviewer NULL）与 SUBMITTED 不占位）
create unique index uq_review_assignment_active_reviewer
    on review_assignment(case_execution_id, reviewer) where status = 'IN_PROGRESS';

create index ix_review_assignment_run on review_assignment(run_id, created_at, id);
create index ix_review_assignment_reviewer on review_assignment(reviewer, status);
create index ix_review_assignment_case on review_assignment(case_execution_id);

-- ---------- 2. review_verdict：评审结果（insert-only；一任务一结论） ----------

create table review_verdict (
    id                 uuid primary key,
    assignment_id      uuid not null,
    run_id             uuid not null,            -- 冗余直挂（V9 FUT-50 禁 JOIN 推导）
    case_execution_id  uuid not null,            -- 冗余直挂（读面按案例聚合不 JOIN）

    reviewer           varchar(64) not null,     -- 评审人（提交时认证主体快照）
    rubric_version     text not null,            -- 冻结 rubric 版本锚（必带，注册表校验）
    verdict            varchar(24) not null
                       check (verdict in ('CORRECT', 'PARTIAL', 'INCORRECT',
                                          'UNDECIDABLE', 'INSUFFICIENT_SOURCE')),
    score              integer check (score between 1 and 5), -- 可空：无法判定不打分
    labels             jsonb not null default '[]'::jsonb
                       check (jsonb_typeof(labels) = 'array'),
    reason             text not null check (length(btrim(reason)) > 0), -- 理由必填
    evidence_refs      jsonb not null default '[]'::jsonb
                       check (jsonb_typeof(evidence_refs) = 'array'),

    created_at         timestamptz not null default now(),

    -- 一任务一结论：提交后不可改，更正/重评分 = 新任务 + 新行
    constraint uq_review_verdict_assignment unique (assignment_id),
    constraint fk_review_verdict_assignment foreign key
        (assignment_id, run_id, case_execution_id)
        references review_assignment (id, run_id, case_execution_id)
);

comment on table review_verdict is
    'EV-08 评审结果（insert-only）：评分/标签/理由 + 冻结 rubric 版本；'
    || '重评分 = 新行不覆盖旧行（审计闭环；不覆盖原机器评分）';

create index ix_review_verdict_run_case on review_verdict(run_id, case_execution_id);

-- ---------- 3. HOLDOUT 计数针孔（security definer；只出聚合计数三列） ----------

create or replace function case_version_partition_counts()
returns table(dataset_version_id uuid, partition_class varchar, case_count bigint)
language sql stable security definer
set search_path = public
as $$
    select cv.dataset_version_id, cv.partition_class, count(*)::bigint
    from case_version cv
    group by cv.dataset_version_id, cv.partition_class
$$;

comment on function case_version_partition_counts() is
    'EV-08 数据集读面 HOLDOUT 计数针孔：RLS 封存下只出 (版本,分区,计数) 聚合，'
    || 'GT 原文与案例行内容永不进投影（函数 owner = 迁移身份，RLS BYPASS）';

revoke all on function case_version_partition_counts() from public;
grant execute on function case_version_partition_counts() to control_app;

-- ---------- 4. 授权（V85/V86 同构；IT 干净库缺角色兜底同 V10） ----------

grant select, insert on review_assignment to control_app;
grant update (reviewer, status, revision, claimed_at, lease_expires_at, submitted_at)
    on review_assignment to control_app;
grant select, insert on review_verdict to control_app;

revoke all on review_assignment from publisher_app, notify_app, public;
revoke all on review_verdict from publisher_app, notify_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'eval_app') then
        revoke all on review_assignment from eval_app;
        revoke all on review_verdict from eval_app;
    end if;
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on review_assignment from arena_app;
        revoke all on review_verdict from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on review_assignment from chaos_admin_app;
        revoke all on review_verdict from chaos_admin_app;
    end if;
end
$$;
