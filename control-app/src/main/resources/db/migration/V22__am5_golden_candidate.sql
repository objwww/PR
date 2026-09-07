-- ============================================================================
-- V22 —— AM5 Golden Candidate 工作流（M5-03；docs/告警AM5-落码技术方案.md §M5-03②）
--   golden_candidate     GT 提案实体（状态机 DRAFT→REVIEW→PUBLISHED/REJECTED/
--                        WITHDRAWN，矩阵见 GoldenCandidateStateMachine；revision
--                        乐观锁 CAS 迁移）
--   golden_review_event  append-only 复核事件（每次迁移一条；idempotency_key
--                        全局唯一 = 幂等重放锚）
--
-- 编号：落码方案迁移表 V22（一迁移一任务，INV-AM5-10）。
--
-- INV-AM5-2 双人复核：
--   - ck_golden_dual_review：同人不能双签的 DB 兜底（reviewer_a = reviewer_b 非
--     空即拒）；应用层 GoldenCandidateService.decide 在进仓储前先拒（更早报错）；
--   - ck_golden_terminal_reviewers：PUBLISHED/REJECTED 必须双签齐全——终态结论
--     不允许单签产生（双签是这两类结论的必要条件）；
--   - 撤回（WITHDRAWN）是提案人单方动作，无双签要求。
--
-- 授权分工（落码方案 §M5-03②）：eval_app 读写候选——候选状态机迁移是 UPDATE 面
-- （V7 列级 UPDATE 惯例：只开口迁移所需列），事件表 append-only 只授 select,insert；
-- 发布动作只向 case_version 追加新行（V20 insert-only 不破，本迁移不新增其授权）。
-- control_app / publisher_app / notify_app / public / arena 域角色零权限。
-- ============================================================================

-- ---------- 1. golden_candidate：GT 提案实体（状态机 + 双签） ----------

create table golden_candidate (
    id               uuid primary key,
    case_version_id  uuid not null references case_version(id),
    proposed_gt      jsonb not null,          -- GT 提案载荷（期望根因/症状码等）
    reason           text not null,           -- 提案理由（人工复核输入）
    state            varchar(16) not null,    -- 状态机（矩阵唯一权威在域层）
    proposed_by      text not null,
    reviewer_a       text,                    -- 双签位 A（发布/拒绝必填）
    reviewer_b       text,                    -- 双签位 B（发布/拒绝必填）
    revision         bigint not null default 0 check (revision >= 0),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),

    constraint ck_golden_candidate_state
        check (state in ('DRAFT','REVIEW','PUBLISHED','REJECTED','WITHDRAWN')),
    -- INV-AM5-2：同人不能双签的 DB 兜底（任一签位为空不触发——空 = 尚未双签）
    constraint ck_golden_dual_review
        check (reviewer_a is null or reviewer_b is null or reviewer_a <> reviewer_b),
    -- 终态结论必须双签齐全（WITHDRAWN 单方撤回除外）
    constraint ck_golden_terminal_reviewers
        check ((state in ('PUBLISHED','REJECTED')
                and reviewer_a is not null and reviewer_b is not null)
            or state in ('DRAFT','REVIEW','WITHDRAWN'))
);

create index ix_golden_candidate_state on golden_candidate(state, updated_at);

comment on table golden_candidate is
    'AM5 GT 提案工作流（M5-03）：双人复核状态机 + revision CAS；'
    || '发布 = case_version 追加新行（insert-only 不破）';

-- ---------- 2. golden_review_event：append-only 复核事件 ----------

create table golden_review_event (
    id                uuid primary key,
    candidate_id      uuid not null references golden_candidate(id),
    action            varchar(16) not null,
    actor             text not null,
    expected_revision bigint not null check (expected_revision >= 0),
    idempotency_key   text not null,          -- 幂等重放锚（同键重放 = 空操作）
    payload           jsonb,                  -- 审计摘要（from/to 等）
    created_at        timestamptz not null default now(),

    constraint ck_golden_review_action
        check (action in ('PROPOSED','SUBMITTED','PUBLISHED','REJECTED','WITHDRAWN')),
    constraint uq_golden_review_event_idem unique (idempotency_key)
);

create index ix_golden_review_event_candidate
    on golden_review_event(candidate_id, created_at);

comment on table golden_review_event is
    'AM5 复核事件时间线（M5-03，append-only 只授 select,insert）：'
    || '同 idempotency_key 唯一，重放判定面';

-- ---------- 3. 授权：eval_app 读写候选（列级 UPDATE）；事件 append-only ----------

grant select, insert on golden_candidate to eval_app;
grant update (
    state, reviewer_a, reviewer_b, revision, updated_at
) on golden_candidate to eval_app;

grant select, insert on golden_review_event to eval_app;

revoke all on golden_candidate, golden_review_event
    from control_app, publisher_app, notify_app, public;
-- arena 域角色条件化幂等 revoke（干净 IT 库可能不存在），V9/V10 同构
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on golden_candidate, golden_review_event from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on golden_candidate, golden_review_event from chaos_admin_app;
    end if;
end
$$;
