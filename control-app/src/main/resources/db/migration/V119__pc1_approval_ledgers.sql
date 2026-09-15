-- PC-C1（Phase C 第一片）：Approval Shadow 审批四账本——Request / decisions /
-- Grant / OperationAuthorization。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md
--   §2.4（R1）：审批绑定稳定事实——action_id/action_digest/run_id/observed_generation/
--     scope_snapshot_hash/policy_version/expires_at；<b>不绑</b>任何瞬时执行身份
--     （worker 属主 / 租约代次 / attempt）——Approval 验 Action，LeaseFence 验
--     Executor，二者不混；
--   §2.5（R8）：三层对象——Request（人类决策对象）→ Grant（可复用授权范围：
--     scope=ONCE/SESSION、expires）→ OperationAuthorization（single-use，一次副作用
--     恰好消费一个）；
--   §2.6（R7）：双人确认 = two distinct principals（一人兼两角色不算）——同人同审批
--     唯一约束 + 事务内 principal/role 双重差异校验；
--   §2.7（生命周期作废矩阵）：换代（observed_generation 漂移）/ policy_version 变更
--     → 审批作废（消费时同事务复核 + 清扫面双保险）；
--   Phase C 铁律：approved → shadow Runner 仍不执行（§5；真实执行随 Phase D）。
--
-- 通知面（5s/15s/45s fail-closed → notify_failed）随 AM8 管理面接入；本波
-- expires 300s 清扫即 fail-closed 兜底（PENDING 超时 = EXPIRED 终态）。

-- ---------- 1. approval_request：人类决策对象（绑定稳定事实，零瞬时执行身份） ----------

create table approval_request (
    request_id        uuid primary key,
    intent_id         uuid not null references action_intent(intent_id),
    run_id            uuid not null,
    action_id         text not null,
    action_digest     text not null,
    observed_generation integer not null,   -- rca_run.generation 快照（换代即作废，§2.7）
    risk              varchar(8) not null,
    required_approvers integer not null,    -- R2=1 / R3=2（two distinct principals）
    scope_snapshot    jsonb not null,
    scope_snapshot_hash text not null,
    policy_version    text not null,
    state             varchar(16) not null default 'PENDING',
    requested_at      timestamptz not null default now(),
    decided_at        timestamptz,
    expires_at        timestamptz not null,  -- 独立 300s（fail-closed 兜底）
    void_reason       text,
    updated_at        timestamptz not null default now(),

    constraint ck_approval_request_state
        check (state in ('PENDING','APPROVED','DENIED','EXPIRED','REVOKED','VOIDED')),
    constraint ck_approval_request_risk check (risk in ('R2','R3')),
    constraint ck_approval_request_required
        check (required_approvers in (1,2)),
    constraint ck_approval_request_lifecycle
        check ((state in ('APPROVED','DENIED')) = (decided_at is not null))
);

create index ix_approval_request_run on approval_request(run_id, requested_at);
create index ix_approval_request_pending on approval_request(state)
    where state = 'PENDING';

comment on table approval_request is
    'PC-C1：审批请求（§2.4）——批的是 digest+快照锚+代数，不批执行身份；'
    'observed_generation/policy_version 任一漂移 = 消费拒绝（作废矩阵 §2.7）';

-- ---------- 2. approval_decisions：独立决策行（R7——JSONB 数组方案否决） ----------

create table approval_decisions (
    decision_id   uuid primary key,
    request_id    uuid not null references approval_request(request_id),
    approver_id   text not null,
    approver_role text not null,
    decision      varchar(8) not null,
    decided_at    timestamptz not null default now(),

    constraint ck_approval_decision check (decision in ('approved','denied')),
    constraint uq_approval_decisions_once UNIQUE (request_id, approver_id)  -- 同一人对同一审批仅一条
);

create index ix_approval_decisions_request on approval_decisions(request_id);

comment on table approval_decisions is
    'PC-C1：双人确认（§2.6）——并发读改写丢更新被 UNIQUE 消灭；'
    '「同人不得批两次」结构化；two distinct principals + distinct roles 在事务内校验';

-- ---------- 3. approval_grant：可复用授权范围（R8——session 语义与 single-use 解耦） ----------

create table approval_grant (
    grant_id         uuid primary key,
    request_id       uuid not null references approval_request(request_id),
    run_id           uuid not null,
    action_id        text not null,
    action_digest    text not null,
    scope_snapshot_hash text not null,
    policy_version   text not null,
    scope_kind       varchar(8) not null default 'ONCE',
    max_operations   integer not null default 1,
    issued_operations integer not null default 0,
    state            varchar(12) not null default 'ACTIVE',
    issued_at        timestamptz not null default now(),
    expires_at       timestamptz not null,
    revoked_at       timestamptz,
    updated_at       timestamptz not null default now(),

    constraint ck_approval_grant_state
        check (state in ('ACTIVE','EXHAUSTED','EXPIRED','REVOKED')),
    constraint ck_approval_grant_scope
        check (scope_kind in ('ONCE','SESSION')),
    constraint ck_approval_grant_quota
        check (issued_operations >= 0 and max_operations >= 1
               and issued_operations <= max_operations),
    constraint ck_approval_grant_once_is_once
        check (scope_kind <> 'ONCE' or max_operations = 1)
);

create index ix_approval_grant_lookup on approval_grant(run_id, action_digest,
    scope_snapshot_hash, policy_version, state) where state = 'ACTIVE';

comment on table approval_grant is
    'PC-C1：授权范围（§2.5）——「可复用的授权范围」与「副作用只能消费一次」'
    '不再冲突：session grant 每次执行签发独立 single-use 授权';

-- ---------- 4. operation_authorization：single-use（消费与 PREPARED 同事务 CAS） ----------

create table operation_authorization (
    authz_id     uuid primary key,
    grant_id     uuid not null references approval_grant(grant_id),
    operation_id uuid,                          -- 消费时回填（§2.8 步骤 2 在 operation 铸造前）
    state        varchar(12) not null default 'ISSUED',
    issued_at    timestamptz not null default now(),
    consumed_at  timestamptz,

    constraint ck_operation_authorization_state check (state in ('ISSUED','CONSUMED')),
    constraint ck_operation_authorization_consumed
        check ((state = 'CONSUMED') = (consumed_at is not null))
);

create index ix_operation_authorization_grant on operation_authorization(grant_id);

comment on table operation_authorization is
    'PC-C1：single-use 授权（§2.5/§2.8）——ISSUED→CONSUMED CAS 在消费模板事务内；'
    '每次副作用恰好消费一个（B/C 组不变量）；grant 过期/撤销后不可签发';

-- ---------- 5. 授权 ----------

grant select, insert, update on approval_request to control_app;
grant select, insert, update on approval_decisions to control_app;
grant select, insert, update on approval_grant to control_app;
grant select, insert, update on operation_authorization to control_app;
