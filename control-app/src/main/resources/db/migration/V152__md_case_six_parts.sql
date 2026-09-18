-- V152 — M-d T5 根因结论六要素落库面（conclusion_six_parts 指标存储）
--
--   纪律依据：.agent-notes/根因结论六要素纪律.md（发生了什么→根因是什么→凭什么
--   判断→影响多大→有多大把握→建议怎么办，缺一不成文）。检出口径=结构面五要素
--   （EvidencePackageV2：summary/root_cause 三元组/claims.evidence_refs/impact/
--   remediation）+ 文本面把握短语（「把握：HIGH|MEDIUM|LOW」，primary v9 写作
--   要求增量锚定，见 deploy/alert/prompts/draft-primary-v9-写作要求增量.md）。
--
--   独立 insert-only 表（沿 V141 eval_case_safety 先例）：不改宽表 eval_case_result
--   （在途改动避让 + 列语义独立）；缺席=无行（早期返回/结构失败不冒充已评）。
--   run 级 six_parts_rate = avg(complete) 聚合（读面后续迁移批次接通）。
--
--   回滚：drop table eval_case_six_parts;

create table eval_case_six_parts (
    id              uuid primary key,
    eval_run_id     uuid not null references eval_run (id),
    scenario_id     text not null,
    round_no        integer not null,
    what_happened   boolean not null,
    root_cause      boolean not null,
    evidence_basis  boolean not null,
    impact          boolean not null,
    confidence      boolean not null,
    recommendation  boolean not null,
    confidence_level varchar(8),
    complete        boolean not null,
    created_at      timestamptz not null default now(),

    constraint uq_eval_case_six_parts unique (eval_run_id, scenario_id, round_no),
    constraint ck_eval_case_six_parts_level
        check (confidence_level in ('HIGH', 'MEDIUM', 'LOW') or confidence_level is null),
    -- 把握布尔与档位同生共死：检出即有档位，未检出即无
    constraint ck_eval_case_six_parts_level_presence
        check (confidence = (confidence_level is not null))
);

comment on table eval_case_six_parts is
    'M-d T5 根因结论六要素（insert-only，逐案例检出版本；缺席=无行如实）';
comment on column eval_case_six_parts.confidence_level is
    '把握档位（HIGH/MEDIUM/LOW），与 confidence 布尔同生共死（level_presence 约束）';

grant select, insert on eval_case_six_parts to eval_app;
grant select on eval_case_six_parts to control_app;
