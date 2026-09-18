-- ============================================================================
-- V145 —— P6-G7 LLM-judge 第三判定式落库面：eval_case_judge（insert-only）
--
-- rubric 二元化（美团法）：三道是/否题只判报告自然语言质量维（结论明确性/自洽性/
-- 可操作性）；四率主指标零接触（结论正确性仍归确定性规则——拒评纪律不变）。
-- verdict 值域 PASS（全题是）/ FAIL（任一否）/ ERROR（模型调用或解析失败，
-- error 列留因）；judge 未启用 = 不落行（缺席=未评如实，不填 0 冒充）。
-- 校准闭环（后续）：与 EV-08 人工盲评同案例对齐算一致率，<0.7 触发 rubric 修订
-- ——rubric_version 列即版本锚（改题面必须升版本）。
-- ============================================================================

create table eval_case_judge (
    id            uuid primary key,
    eval_run_id   uuid not null references eval_run (id),
    scenario_id   text not null,
    round_no      integer not null,
    rubric_version text not null,
    model         text not null,
    answers       jsonb not null default '[]',
    passed        integer,
    total         integer,
    verdict       text not null,
    error         text,
    created_at    timestamptz not null default now(),

    constraint uq_eval_case_judge unique (eval_run_id, scenario_id, round_no),
    constraint ck_eval_case_judge_verdict check (verdict in ('PASS', 'FAIL', 'ERROR'))
);

comment on table eval_case_judge is
    'P6-G7 LLM-judge 第三判定式（rubric 二元化；insert-only）：报告自然语言质量维裁决——四率主指标零接触，缺席=未评如实';

grant select, insert on eval_case_judge to eval_app;
grant select on eval_case_judge to control_app;
