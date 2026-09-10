-- ============================================================================
-- V48 —— R7a-1 模型网关 RCA 适配：rca_model_call 账本（v2.1 §二，F02）
--
-- RCA 域自有模型调用账本，与平台 model_call（PR 域）分账不分流：同一 ModelGateway
-- 执行面（路由/重试/熔断复用），每物理请求单独一行单独计费（§二.4），invocation_id
-- 回填平台账本 invocationId（跨账对账锚）。
-- 语义锚（EX-A0 动作身份 + EX-A3 恢复共用）：
--   1) PENDING 先行落账取得发送资格——账本不可写 = 零触网（§二.3，D5 同律）；
--   2) 四态 PENDING/SUCCESS/FAILED/UNKNOWN——沿 V15 工具账本四态惯例
--      （方案原文 "STARTED" 映射为本库 PENDING，偏差在执行日志留痕）；
--   3) usage 缺失不猜零：SUCCESS + usage_missing=true，费用未决（对账面，RX19）；
--   4) UNKNOWN = 是否已执行不确定 → 保守占预算，恢复对账不盲重发（RX14/RD14 面）。
-- uq(run, task, attempt, action_seq, physical_seq)：逻辑动作 × 物理请求身份
-- （每个物理重试单独预留单独计费）；无 attempt 的模型调用不存在（调用必在租约内）。
-- agent_id/agent_version（方案原文列名）按 §二.2 映射为 role_id/role_version 落列，
-- 不维护两套身份语义。
-- 回滚：drop 表（先滚应用后滚库）。编号纪律：rebase 时以下一可用号为准替换 V48。
-- ============================================================================

create table rca_model_call (
    id                     uuid primary key,
    run_id                 uuid not null references rca_run(id),
    task_id                uuid not null references rca_task(id),
    attempt_id             uuid not null references rca_attempt(id),
    action_seq             bigint not null,        -- 逻辑动作序（主任务=决策步序）
    physical_seq           integer not null default 1, -- 物理请求序（每次重试单独一行）
    round_id               integer not null default 0,
    role_id                text not null,
    role_version           text not null,
    role_digest            char(64) not null,
    prompt_digest          char(64) not null,      -- 提示词正文摘要（不落原文）
    route_id               text,                   -- 成功/失败终态回填（PENDING 可空）
    requested_model        text,
    provider_request_id    text,                   -- 供应商回执（可缺，usage_missing 同源）
    invocation_id          uuid,                   -- 平台 gateway invocationId（跨账对账）
    budget_reservation_id  uuid,                   -- ActionGuard 预算预留锚（R7a-2 接线）
    input_snapshot_digest  char(64),
    config_epoch           bigint,
    release_digest         char(64),
    lease_epoch            bigint not null default 0,
    state                  text not null default 'PENDING',
    usage                  jsonb,                  -- {prompt_tokens,completion_tokens,total_tokens}
    usage_missing          boolean not null default false,
    cost_micros            bigint,                 -- usage 缺失时不填（不猜零）
    pricing_version        text,
    currency               text,
    latency_ms             bigint,
    error_code             text,                   -- FAILED/UNKNOWN 原因码（脱敏，非供应商原文）
    created_at             timestamptz not null,
    settled_at             timestamptz,

    constraint uq_rca_model_call_action
        unique (run_id, task_id, attempt_id, action_seq, physical_seq),
    constraint ck_rca_model_call_state
        check (state in ('PENDING', 'SUCCESS', 'FAILED', 'UNKNOWN')),
    constraint ck_rca_model_call_seq check (action_seq >= 0 and physical_seq >= 1
        and round_id >= 0),
    constraint ck_rca_model_call_no_fake_cost
        check (usage_missing = false or cost_micros is null)
);

create index ix_rca_model_call_run_state on rca_model_call(run_id, state);
create index ix_rca_model_call_task on rca_model_call(run_id, task_id, action_seq);

comment on table rca_model_call is
    'R7a-1 RCA 模型调用账本（PENDING 先行=发送资格/账本不可写零触网/usage 缺失不猜零/UNKNOWN 保守占预算）';

-- 授权（V7/V46/V47 同构）：账本行 PENDING→终态单向（update 只服务终态 CAS）
grant select, insert, update on rca_model_call to control_app;
revoke all on rca_model_call from publisher_app;
revoke all on rca_model_call from public;
