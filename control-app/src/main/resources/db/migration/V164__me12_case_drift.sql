-- ============================================================================
-- V164 —— ME-T12（D08）：逐案上下文漂移评测落库面 eval_case_drift
--         + 压缩消费观测 append 面 rca_compaction_consumption
--
--   上下文漂移（压缩前后事实保真/使用面一致性）成为可评分输入：六检查
--   KEY_FACT_RETENTION/COUNTER_EVIDENCE/FACT_DISTORTION/CONSTRAINT/
--   EVIDENCE_UPDATE/RE_ERROR（五态，缺证据不猜通过）+ 分子分母 metrics +
--   MAST 失败标签 + 四条 deferred（task_quality/total_cost/position_length/
--   injection_success——本 grader 不评，如实留痕）。独立 insert-only 表
--   （沿 V163 eval_case_collab 先例）：eval_case_result 宽表契约是六维冻结
--   列，grader 版本化重评并存（同案例多 grader 行）放不进一行一案的宽表。
--
--   键面：case_result_id 外键指向 eval_case_result(id)（同 D04 第 6 条）；唯一键
--   (case_result_id, grader_version)——同轨迹同 grader 重算幂等（on conflict
--   do nothing），换 grader 版本新行并存、历史不被重评覆盖。eval_run_id/
--   scenario_id/round_no 冗余自然键随表。summary_digest 可空 = 无压缩事件
--   （NO_SUMMARY）或观测读失败 ERROR 行（不出数不填空串）；consumption jsonb
--   无消费观测时如实落 null。
--
--   同文件并表 rca_compaction_consumption（CL-07 消费观测 append 面）：
--   生产主链 BoundedLlmRoleRunner.maybeCompact 在 COMMITTED 且带消费观测时
--   逐次 append 一行（mode/summary_committed/consumer_invoked/consumed/
--   policy_digest 五件如实），评测侧按 run 取最新行拼 ConsumptionFace 作
--   评分输入。写入身份 = control_app（alert 主链数据源，同 V15/V47/V92
--   授权对象一致）；eval_app 只读（评分投影源）。
--
--   授权面：eval_case_drift 写身份 = eval_app（同 V163）；control_app 只读
--   （UI/查询投影）。随表补投影源只读授权（沿 V163 先例）：
--   rca_context_summary（V92，评分投影读最新摘要 summary_text/digest）——
--   建表时只授 control_app（V92 头注明说 eval 不授），本迁移是评测域首次
--   消费该表，随首用补 SELECT 并在此声明。零写开口。
--
--   回滚：drop table eval_case_drift;
--         drop table rca_compaction_consumption;
--         revoke select on rca_context_summary from eval_app;
-- ============================================================================

create table eval_case_drift (
    id                       uuid primary key,
    case_result_id           uuid not null references eval_case_result (id),
    eval_run_id              uuid not null references eval_run (id),
    scenario_id              text not null,
    round_no                 integer not null,
    grader_version           varchar(64) not null,
    summary_digest           varchar(64),
    consumption              jsonb not null,
    checks                   jsonb not null,
    metrics                  jsonb not null,
    failure_labels           jsonb not null,
    deferred                 jsonb not null,
    created_at               timestamptz not null default now(),

    constraint uq_eval_case_drift unique (case_result_id, grader_version)
);

comment on table eval_case_drift is
    'ME-T12（D08）逐案上下文漂移评测（insert-only；uq(案例, grader版本) 重评并存不覆盖历史；checks 每项 status/reasonCode/证据引用，metrics 每项分子/分母，consumption 无消费观测如实 null，deferred 四条本 grader 不评留痕）';
comment on column eval_case_drift.summary_digest is
    'rca_context_summary.summary_digest（正文 sha256 锚）；NULL=无压缩事件或观测读失败 ERROR 行';
comment on column eval_case_drift.deferred is
    '四条 deferred 键面（task_quality_change/total_cost_change/position_length_buckets/injection_attack_success_rate）：本 grader 明确不评，如实留痕防“漏评”误读';

grant select, insert on eval_case_drift to eval_app;
grant select on eval_case_drift to control_app;

create table rca_compaction_consumption (
    id                       uuid primary key,
    run_id                   uuid not null,
    task_id                  uuid not null,
    summary_digest           varchar(64) not null,
    mode                     varchar(32) not null,
    summary_committed        boolean not null,
    consumer_invoked         boolean not null,
    consumed                 boolean,
    policy_digest            varchar(64),
    created_at               timestamptz not null default now()
);

comment on table rca_compaction_consumption is
    'CL-07 压缩消费观测 append 面（insert-only；写入方=alert 主链 BoundedLlmRoleRunner；consumed NULL=未观测不猜；policy_digest=消费策略锚可空）；评测侧按 run 取最新行作 ContextDrift 评分输入';

grant select, insert on rca_compaction_consumption to control_app;
grant select on rca_compaction_consumption to eval_app;

-- 投影源只读授权（评测域首次消费 V92 摘要档；零写开口）
grant select on rca_context_summary to eval_app;
