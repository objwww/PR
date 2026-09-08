-- ============================================================================
-- V30 —— AM6 M6-01 Canary 证据分级 + 窗口判定（release 域）
--   canary_evidence_sample   采集样本 append-only（生产 provenance 可追、原始
--                            指标不预聚合覆盖；run_id 唯一 = 一 run 一样本）
--   canary_window_verdict    窗口判定草案 append-only（稳定候选身份三 digest、
--                            证据分级、分层原始计数、双门明细；uq_cwv_window
--                            = 同窗幂等——重评不重记）
--   INV-AM6-5：DRILL/REPLAY 证据只记录不晋升（Evaluator 仅数 LIVE_CANARY）；
--   引擎对照/回退/退场分属 V31~V33。
--
-- 编号说明：落码方案 §迁移号段分配表（v1.1 G1 修正；v1.1r1 对账）。
-- 对账注：原 V30 草案承担的唯一活跃索引重建（BA-40）已由 V25 就地修复关闭
-- （14edd86①，195 实证），本迁移不再动任何索引（Am6MigrationContractTest 门控）。
-- ============================================================================

-- ---------- 1. canary_evidence_sample：采集样本（append-only） ----------

create table canary_evidence_sample (
    id               bigserial primary key,
    run_id           uuid not null unique references rca_run(id),
    incident_id      uuid not null,
    stickiness_key   text not null,
    evidence_class   varchar(16) not null,
    provenance_json  jsonb not null,
    observed_json    jsonb not null,
    created_at       timestamptz not null,
    constraint ck_ces_class check (evidence_class in ('LIVE_CANARY','DRILL','REPLAY'))
);

comment on table canary_evidence_sample is
    'AM6 M6-01 Canary 采集样本（insert-only；evidence_class 分级 LIVE_CANARY/DRILL/REPLAY，INV-AM6-5 仅 LIVE 计入晋升）';
comment on column canary_evidence_sample.provenance_json is
    '采集出处（inbox/tenant/test-marker/采集器版本）——LIVE 必须可追到生产事实';
comment on column canary_evidence_sample.observed_json is
    '原始指标（不预聚合覆盖，重评可回放）';

-- ---------- 2. canary_window_verdict：窗口判定（append-only，同窗幂等） ----------

create table canary_window_verdict (
    id              bigserial primary key,
    rollout_id      uuid        not null,
    candidate_digest char(64)   not null,
    rollout_policy_digest char(64) not null,
    capability_digest char(64)  not null,
    from_percent    int         not null,
    to_percent      int         not null,
    window_seq      int         not null,
    window_start    timestamptz not null,
    window_end      timestamptz,
    evidence_class  varchar(16) not null,
    eligible_incidents int      not null default 0,
    raw_counts      jsonb       not null,
    strata_json     jsonb       not null,
    control_json    jsonb       not null,
    absolute_slo_json jsonb     not null,
    critical_pass   boolean,
    scored_json     jsonb,
    verdict         varchar(16) not null,
    evidence_refs   jsonb       not null default '[]',
    evaluated_at    timestamptz not null default now(),
    constraint ck_cwv_class check (evidence_class in ('LIVE_CANARY','DRILL','REPLAY')),
    constraint ck_cwv_verdict check (verdict in ('PASS','FAIL','INCONCLUSIVE')),
    constraint uq_cwv_window unique
      (rollout_id,candidate_digest,rollout_policy_digest,capability_digest,
       from_percent,to_percent,window_seq,evidence_class)
);

comment on table canary_window_verdict is
    'AM6 M6-01 Canary 窗口判定（insert-only；candidate/rollout-policy/capability 三 digest 稳定候选身份，digest 任一变化 = 新身份作废旧矩阵）';
comment on column canary_window_verdict.candidate_digest is
    '行为候选 digest（不含 rollout percent——比例调整不作废在途窗）';
comment on column canary_window_verdict.rollout_policy_digest is
    '阈值/窗口/比例策略 digest（canary.window 段，O-63 全量 bundle 版本化禁硬编码）';
comment on column canary_window_verdict.capability_digest is
    '实际装配能力指纹 digest（NativeCapabilityProbe 产物）';
comment on column canary_window_verdict.eligible_incidents is
    '按 incident/stickinessKey 聚类后的独立样本数（非原始样本条数）';
comment on column canary_window_verdict.raw_counts is
    '分子/分母/排除原因（INSUFFICIENT_SAMPLES/NO_CONTROL_COHORT）等原始计数';
comment on column canary_window_verdict.strata_json is
    'tenant/severity/scenario 覆盖分层计数';
comment on column canary_window_verdict.control_json is
    '同时段 Holmes cohort 相对门明细（native_fail_rate/control_fail_rate/tolerance/gate）';
comment on column canary_window_verdict.absolute_slo_json is
    '绝对 SLO 门明细（native_fail_rate/max_absolute_fail_rate/gate）';
comment on column canary_window_verdict.critical_pass is
    'critical 安全门（null=无 critical 证据；false 一票否决 FAIL）';
comment on column canary_window_verdict.scored_json is
    '连续 K 窗评分产物（M6-03 窗口任务回填，M6-01 留空）';
comment on column canary_window_verdict.verdict is
    '窗判定 PASS/FAIL/INCONCLUSIVE（INCONCLUSIVE=缺数不评判，不落 FAIL）';

-- ---------- 3. 授权（V7/V25 惯例：证据表只 select,insert） ----------

grant select, insert on canary_evidence_sample to control_app;
revoke update, delete on canary_evidence_sample from control_app;
revoke all on canary_evidence_sample
    from publisher_app, notify_app, eval_app, public;
-- BA-42①（195 真 PG 实证：append 走 bigserial 默认值，表授权不覆盖序列面，
-- 漏 USAGE 即生产写路径 permission denied）
grant usage on sequence canary_evidence_sample_id_seq to control_app;
revoke all on sequence canary_evidence_sample_id_seq
    from publisher_app, notify_app, eval_app, public;

grant select, insert on canary_window_verdict to control_app;
revoke update, delete on canary_window_verdict from control_app;
revoke all on canary_window_verdict
    from publisher_app, notify_app, eval_app, public;
grant usage on sequence canary_window_verdict_id_seq to control_app;
revoke all on sequence canary_window_verdict_id_seq
    from publisher_app, notify_app, eval_app, public;
