-- ============================================================================
-- V163 —— ME-T12（D07）：逐案多 Agent 协作评测落库面 eval_case_collab
--
--   交接边投影成为可评分输入：委派必要性/跨模态支持/冲突处置/交接保留率/有界
--   降级/回执幂等/取消围栏/重复取证/证据消费/错误遏制/臂可比性/消融单因子/
--   干预重放十三项检查（五态，缺证据不猜通过）+ 分子分母 metrics + MAST 失败
--   标签 + 归因双轨（suspected=疑似无干预对照 / supported=重放改善因果归因，
--   分列不混）。独立 insert-only 表（沿 V162 eval_case_loop 先例）：
--   eval_case_result 宽表契约是六维冻结列，grader 版本化重评并存（同案例多
--   grader 行）放不进一行一案的宽表。
--
--   键面：case_result_id 外键指向 eval_case_result(id)（同 D04 第 6 条）；唯一键
--   (case_result_id, grader_version)——同轨迹同 grader 重算幂等（on conflict
--   do nothing），换 grader 版本新行并存、历史不被重评覆盖。eval_run_id/
--   scenario_id/round_no 冗余自然键随表。edge_count/admitted_count/
--   token_cost_total 可空 = 观测读失败 ERROR 行或 trace 缺失（不出数不填 0）。
--
--   授权面：评分链写身份 = eval_app（同 V162）；control_app 只读（UI/查询投影）。
--   随表补投影源只读授权（沿 V157 先例）：协作投影读 rca_delegation_decision
--   （V47）/ rca_delegation_receipt（V96）/ rca_task（V7，取消围栏推导）——
--   三表建表时只授 control_app，eval 身份缺 SELECT 会被评分侧 catch 吞成
--   ERROR 行假面。零写开口。
--
--   回滚：drop table eval_case_collab;
--         revoke select on rca_delegation_decision from eval_app;
--         revoke select on rca_delegation_receipt from eval_app;
--         revoke select on rca_task from eval_app;
-- ============================================================================

create table eval_case_collab (
    id                       uuid primary key,
    case_result_id           uuid not null references eval_case_result (id),
    eval_run_id              uuid not null references eval_run (id),
    scenario_id              text not null,
    round_no                 integer not null,
    grader_version           varchar(64) not null,
    edge_count               integer,
    admitted_count           integer,
    token_cost_total         bigint,
    checks                   jsonb not null,
    metrics                  jsonb not null,
    failure_labels           jsonb not null,
    suspected_attributions   jsonb not null,
    supported_attributions   jsonb not null,
    created_at               timestamptz not null default now(),

    constraint uq_eval_case_collab unique (case_result_id, grader_version)
);

comment on table eval_case_collab is
    'ME-T12（D07）逐案多 Agent 协作评测（insert-only；uq(案例, grader版本) 重评并存不覆盖历史；checks 每项 status/reasonCode/证据引用，metrics 每项分子/分母，归因双轨 suspected/supported 分列）';
comment on column eval_case_collab.edge_count is
    '交接边数（APPROVED 委派决策行）；NULL=观测读失败 ERROR 行或 trace 缺失';
comment on column eval_case_collab.admitted_count is
    '准入 ACCEPTED 回执边数；NULL=同上未观测';
comment on column eval_case_collab.token_cost_total is
    '各角色 token 成本合计（rca_model_call 按子任务归组）；任一边成本缺失如实 NULL 不出数';

grant select, insert on eval_case_collab to eval_app;
grant select on eval_case_collab to control_app;

-- 投影源只读授权（沿 V157 先例；零写开口）
grant select on rca_delegation_decision to eval_app;
grant select on rca_delegation_receipt to eval_app;
grant select on rca_task to eval_app;
