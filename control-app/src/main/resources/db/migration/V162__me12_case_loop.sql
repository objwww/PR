-- ============================================================================
-- V162 —— ME-T12（D05）：逐案死循环评测落库面 eval_case_loop
--
--   run 级无进展窗口离线测量成为可评分输入：loop_detection / loop_false_positive
--   / safe_stop / post_stop_new_actions / normal_task_completion 五项检查（五态，
--   缺证据不猜通过）+ 观测标量（stop_reason/detection_event_index/
--   first_no_progress_event_index/post_stop_new_actions + onset 起算成本三件）
--   + 分子分母 metrics。独立 insert-only 表（沿 V161 eval_case_behavior 先例）：
--   eval_case_result 宽表契约是六维冻结列，grader 版本化重评并存（同案例多
--   grader 行）放不进一行一案的宽表。
--
--   键面：case_result_id 外键指向 eval_case_result(id)（同 D04 第 6 条）；唯一键
--   (case_result_id, grader_version)——同轨迹同 grader 重算幂等（on conflict
--   do nothing），换 grader 版本新行并存、历史不被重评覆盖。eval_run_id/
--   scenario_id/round_no 冗余自然键随表（run 级聚合读面与各 facet 表同构，
--   不强制回跳宽表）。stop_reason/detection_event_index 等标量可空 = 观测读
--   失败 ERROR 行或无可评轨迹（无投影可出数）。
--
--   授权面：评分链写身份 = eval_app（同 V161）；control_app 只读（UI/查询投影）。
--
--   回滚：drop table eval_case_loop;
-- ============================================================================

create table eval_case_loop (
    id                            uuid primary key,
    case_result_id                uuid not null references eval_case_result (id),
    eval_run_id                   uuid not null references eval_run (id),
    scenario_id                   text not null,
    round_no                      integer not null,
    grader_version                varchar(64) not null,
    stop_reason                   varchar(32),
    detection_event_index         integer,
    first_no_progress_event_index integer,
    post_stop_new_actions         integer not null default 0,
    physical_calls_from_onset     bigint,
    tokens_from_onset             bigint,
    seconds_from_onset            bigint,
    checks                        jsonb not null,
    metrics                       jsonb not null,
    failure_labels                jsonb not null,
    created_at                    timestamptz not null default now(),

    constraint uq_eval_case_loop unique (case_result_id, grader_version)
);

comment on table eval_case_loop is
    'ME-T12（D05）逐案死循环评测（insert-only；uq(案例, grader版本) 重评并存不覆盖历史；checks 每项 status/reasonCode/证据引用，metrics 每项分子/分母）';
comment on column eval_case_loop.stop_reason is
    '终态：LOOP_NO_PROGRESS=循环检出停 / BUDGET_EXHAUSTED=预算兜底停（不冒充检出）/ COMPLETED=正常完成；NULL=观测读失败 ERROR 行或无可评轨迹';
comment on column eval_case_loop.detection_event_index is
    '检出事件序（无进展窗达窗事件）；NULL=未检出或不可评';
comment on column eval_case_loop.first_no_progress_event_index is
    '导致停止的无进展窗口起点（首次无进展事件序）；NULL=未停止或不可评';
comment on column eval_case_loop.post_stop_new_actions is
    '停止被接受后新发起的工具/模型动作数（目标 0；在途晚到结果单列核验不算新动作，不落本列）';
comment on column eval_case_loop.physical_calls_from_onset is
    'onset（无标注则以首事件起算正常消耗参照）至终态的物理调用数';
comment on column eval_case_loop.tokens_from_onset is
    'onset 至终态 token 成本；任一事件成本缺失如实 NULL 不出数';
comment on column eval_case_loop.seconds_from_onset is
    'onset 至终态耗时秒；事件时钟缺失如实 NULL 不出数';

grant select, insert on eval_case_loop to eval_app;
grant select on eval_case_loop to control_app;
