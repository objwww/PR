-- ============================================================================
-- V10 —— AM3 评测持久化（M3-14；docs/告警AM3-落码技术方案.md v1.1）
--   eval_run          一次批量评测（5×2）+ 十项可复现元数据 + 完成时回填的
--                     原始 TP/FP/FN 与三指标分子分母（不只存最终小数）
--   eval_case_result  场景×轮逐案例评分（insert-only；同 case 两轮 = 两条独立记录，
--                     UNIQUE(eval_run_id, scenario_id, round_no) 禁覆盖）
--
-- 编号说明：落码方案 v1.1 原文写"独立迁移 V9__am3_eval_run.sql"，V9 编号已被
-- V9__am3_eval_notify.sql 占用（v1.1 迁移拆分本身的落点），本文顺延为 V10。
--
-- 不可覆盖语义（M3-14 验收）：
--   - eval_run.id 主键禁重生：同 id 二次 INSERT 直接违约；
--   - 聚合指标只在终态化一次性回填（CAS state='RUNNING' → 终态，0 行拒绝）；
--   - eval_case_result 无 UPDATE 授权（只 select,insert）——评分记录落库即冻结，
--     重评 = 换新 EvalRun；
--   - "重跑 digest 一致"锚 = eval_run.config_digest（十项元数据的 canonical 摘要，
--     域侧 EvalRunMetadata.configDigest）。
--
-- 授权分工：eval_app = eval-runner 独立身份（M3-15），生产 control_app /
-- notify_app / arena 域角色一律零权限（评测面与生产面隔离）。
-- ============================================================================

-- ---------- 1. eval_run：批量评测运行头（元数据 + 终态聚合） ----------

create table eval_run (
    id                uuid primary key,
    schema_version    integer not null check (schema_version > 0),  -- eval 契约版本

    -- 十项可复现元数据（M3-14 冻结清单；config_digest = 全体的 canonical 摘要）
    dataset_version   text not null,
    registry_digest   char(64) not null,   -- eval-scenarios.yml 内容摘要（M3-10 contentDigest）
    lexicon_version   integer not null check (lexicon_version > 0),
    model             text not null,
    prompt_version    text not null,
    prompt_digest     char(64) not null,
    tool_registry_digest char(64) not null,
    temperature       numeric,             -- provider 不支持/未配置时 NULL（诚实留空）
    top_p             numeric,
    max_tokens        integer check (max_tokens is null or max_tokens > 0),
    requested_seed    bigint,
    effective_seed    bigint,              -- provider 实际生效种子（未回传则 NULL）
    provider_fingerprint text not null,    -- LiteLLM/上游端点指纹（M3-25 对账输入）
    alert_rule_digest char(64) not null,   -- 评测期 Prometheus 规则集摘要
    scenario_driver_version text not null,
    config_digest     char(64) not null,   -- 上述全体的 canonical 摘要（重跑一致锚）

    state             varchar(16) not null,
    started_at        timestamptz not null,
    finished_at       timestamptz,

    -- 终态化一次性回填：原始 TP/FP/FN（症状维度）+ 三指标分子分母 + 比率
    total_scenarios   integer check (total_scenarios > 0),
    decidable_count   integer check (decidable_count >= 0),
    hit_count         integer check (hit_count >= 0),
    unresolved_count  integer check (unresolved_count >= 0),
    structure_rejected_count integer check (structure_rejected_count >= 0),
    timeout_or_absent_count  integer check (timeout_or_absent_count >= 0),
    tp_count          integer check (tp_count >= 0),
    fp_count          integer check (fp_count >= 0),
    fn_count          integer check (fn_count >= 0),
    coverage            double precision,
    conditional_accuracy double precision,
    end_to_end_hit_rate double precision,
    unresolved_rate     double precision,
    baseline_report_digest char(64),       -- M3-18 基线报告内容摘要

    created_at        timestamptz not null default now(),

    constraint ck_eval_run_state check (state in ('RUNNING','SUCCEEDED','FAILED')),
    -- RUNNING 未终结（无任何聚合）；SUCCEEDED 必带全套指标；FAILED 允许无指标（中途夭折）
    constraint ck_eval_run_lifecycle check (
        (state = 'RUNNING' and finished_at is null and total_scenarios is null)
        or (state = 'SUCCEEDED' and finished_at is not null and total_scenarios is not null
            and decidable_count is not null and hit_count is not null
            and unresolved_count is not null and structure_rejected_count is not null
            and timeout_or_absent_count is not null
            and tp_count is not null and fp_count is not null and fn_count is not null
            and coverage is not null and conditional_accuracy is not null
            and end_to_end_hit_rate is not null and unresolved_rate is not null)
        or (state = 'FAILED' and finished_at is not null))
);

create index ix_eval_run_started on eval_run(started_at desc);

-- ---------- 2. eval_case_result：场景×轮逐案例评分（insert-only） ----------

create table eval_case_result (
    id            uuid primary key,
    eval_run_id   uuid not null references eval_run(id),
    scenario_id   text not null,
    round_no      integer not null check (round_no >= 1),

    -- 评分对象选择规则（M3-16 冻结，评审 P0-7）：绑定 Run 状态机最终选定者，禁挑最优
    selection_policy_version text not null,
    rca_run_id        uuid references rca_run(id),
    scored_attempt_id uuid references rca_attempt(id),
    scored_report_id  uuid references rca_report(id),

    verdict         varchar(20) not null,   -- ScenarioMetrics.ScoringVerdict 四值
    root_cause_hit  boolean not null,

    expected_root_cause      jsonb not null, -- 注册表期望三元组快照
    actual_root_cause        jsonb,          -- 报告类型化根因（无报告 = NULL）
    expected_symptom_codes   jsonb not null,
    actual_symptom_codes     jsonb,
    tp_count       integer not null default 0 check (tp_count >= 0),
    fp_count       integer not null default 0 check (fp_count >= 0),
    fn_count       integer not null default 0 check (fn_count >= 0),
    latency_ms     bigint check (latency_ms is null or latency_ms >= 0),
    silence_penalty boolean not null default false,  -- tool_calls 空而 claims 非空
    failure_sample jsonb,                   -- M-04 NO_MATCH 原值 / 失败原文（M3-18 报告引用）

    created_at     timestamptz not null default now(),

    -- 同 case 两轮 = 两条独立记录；同 (run, scenario, round) 禁第二条 = 禁覆盖
    constraint uq_eval_case_result unique (eval_run_id, scenario_id, round_no),

    constraint ck_eval_case_verdict check (verdict in
        ('DECIDABLE','UNRESOLVED','STRUCTURE_REJECTED','TIMEOUT_OR_ABSENT')),
    -- 判定形态与评分对象一致：DECIDABLE 必有选定报告；UNRESOLVED 有报告（谨慎拒答）；
    -- 结构失败/缺席无有效报告可选
    constraint ck_eval_case_verdict_shape check (
        (verdict = 'DECIDABLE' and scored_report_id is not null and scored_attempt_id is not null)
        or (verdict = 'UNRESOLVED' and scored_report_id is not null)
        or (verdict in ('STRUCTURE_REJECTED','TIMEOUT_OR_ABSENT')
            and scored_report_id is null)),
    constraint ck_eval_case_hit_only_decidable
        check (not root_cause_hit or verdict = 'DECIDABLE')
);

create index ix_eval_case_scenario on eval_case_result(scenario_id, created_at);

-- ---------- 3. 授权：eval_app 独立身份；生产角色全部零权限 ----------

grant select, insert on eval_run to eval_app;
grant update (
    state, finished_at,
    total_scenarios, decidable_count, hit_count, unresolved_count,
    structure_rejected_count, timeout_or_absent_count,
    tp_count, fp_count, fn_count,
    coverage, conditional_accuracy, end_to_end_hit_rate, unresolved_rate,
    baseline_report_digest
) on eval_run to eval_app;
grant select, insert on eval_case_result to eval_app;

revoke all on eval_run, eval_case_result
    from control_app, publisher_app, notify_app, public;
-- arena 域角色在 control-only 干净库（IT 的 Testcontainers 库）可能不存在——
-- REVOKE 对不存在角色报 42704，故条件化（幂等；真实部署两角色由 01-roles.sh 创建）
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on eval_run, eval_case_result from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on eval_run, eval_case_result from chaos_admin_app;
    end if;
end
$$;
