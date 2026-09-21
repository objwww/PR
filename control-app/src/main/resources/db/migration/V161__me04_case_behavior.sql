-- ============================================================================
-- V160 —— ME-T04（D04）：逐案行为评测落库面 eval_case_behavior
--
--   轨迹成为可评分输入：引用附带率/引用存在/同 run 归属/时窗有效/支持反驳（v1
--   词法启发式）/证据检查点覆盖六项检查 + 双轨覆盖（旧版文本覆盖 vs 新证据覆盖）
--   + 分子分母 metrics。独立 insert-only 表（沿 V141 eval_case_safety /
--   V152 eval_case_six_parts 先例）：eval_case_result 宽表契约是六维冻结列，
--   grader 版本化重评并存（同案例多 grader 行）放不进一行一案的宽表。
--
--   键面：case_result_id 外键指向 eval_case_result(id)（D04 第 6 条）；唯一键
--   (case_result_id, grader_version)——同轨迹同 grader 重算幂等（on conflict
--   do nothing），换 grader 版本新行并存、历史不被重评覆盖。eval_run_id/
--   scenario_id/round_no 冗余自然键随表（run 级聚合读面与各 facet 表同构，
--   不强制回跳宽表）。trace_digest 可空 = 观测读失败 ERROR 行（无投影可摘要）。
--
--   授权面：评分链写身份 = eval_app（同 V152）；control_app 只读（UI/查询投影）。
--
--   回滚：drop table eval_case_behavior;
-- ============================================================================

create table eval_case_behavior (
    id              uuid primary key,
    case_result_id  uuid not null references eval_case_result (id),
    eval_run_id     uuid not null references eval_run (id),
    scenario_id     text not null,
    round_no        integer not null,
    grader_version  varchar(64) not null,
    trace_digest    varchar(64),
    coverage        jsonb not null,
    checks          jsonb not null,
    metrics         jsonb not null,
    failure_labels  jsonb not null,
    evidence_refs   jsonb not null,
    created_at      timestamptz not null default now(),

    constraint uq_eval_case_behavior unique (case_result_id, grader_version)
);

comment on table eval_case_behavior is
    'ME-T04（D04）逐案行为评测（insert-only；uq(案例, grader版本) 重评并存不覆盖历史；checks 每项 status/reasonCode/证据引用，metrics 每项分子/分母）';
comment on column eval_case_behavior.trace_digest is
    '轨迹投影内容摘要（事件 ID/顺序+证据 digest 确定性哈希）；NULL=观测读失败 ERROR 行';
comment on column eval_case_behavior.coverage is
    '检查点覆盖双轨：text*=旧版文本覆盖（报告子串，原义保留），evidence*=新证据覆盖';

grant select, insert on eval_case_behavior to eval_app;
grant select on eval_case_behavior to control_app;
