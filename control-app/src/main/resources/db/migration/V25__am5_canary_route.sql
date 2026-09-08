-- ============================================================================
-- V25 —— AM5 M5-10 Canary Router 稳定分桶（release 域）
--   rca_run 路由四列            engine（HOLMES 主路径 / NATIVE 候选）、
--                              config_digest（Run 启动固定不再变——回滚只影响新 Run）、
--                              stickiness_key、canary_bucket
--   uq_rca_run_active_incident  唯一活跃索引升 (incident_id, engine) 粒度——
--                              存量行/Shadow（engine 默认 HOLMES）行为不变，
--                              NATIVE 候选获得独立槽位（Shadow 与 Canary 关系
--                              裁定 = 开放项 O-3，裁定前 Shadow 恒默认桶）
--   canary_route_decision       路由决策 append-only 审计（决策/比例/bucket/
--                              digest 全记录，E2E-AM5-05 断言面）
--
-- 编号说明：落码方案 §M5-10② 新增号段（矛盾点 C-2：V7 rca_run 无 engine/config
-- digest 列，uq 单引擎粒度与 Canary 双路并存违约）。
-- ============================================================================

-- ---------- 1. rca_run 路由四列（存量行回填默认 HOLMES，行为零变） ----------

alter table rca_run add column engine varchar(16) not null default 'HOLMES';
alter table rca_run add column config_digest char(64);
alter table rca_run add column stickiness_key text;
alter table rca_run add column canary_bucket integer;

alter table rca_run
    add constraint ck_rca_run_engine check (engine in ('HOLMES','NATIVE'));

-- NATIVE 必带 config_digest（Run 启动固定；域面 RcaRunRouting 同构校验的 DB 兜底）
alter table rca_run
    add constraint ck_rca_run_native_digest
        check (engine <> 'NATIVE' or config_digest is not null);

comment on column rca_run.engine is
    'AM5 M5-10 调查引擎（HOLMES 主路径 / NATIVE 候选——check 值域 HOLMES/NATIVE；Run 启动固定不再变，回滚只影响新 Run）';
comment on column rca_run.config_digest is
    'AM5 M5-10 Run 启动时的 active bundle digest（固定快照——在途 Run 不换 engine digest；老 Run 固定旧 digest）';
comment on column rca_run.stickiness_key is
    'AM5 M5-10 归一化 stickiness 键（groupId:id，CanaryBucketer.normalizedKey 产物；无 key = 拒绝放量走 HOLMES）';
comment on column rca_run.canary_bucket is
    'AM5 M5-10 无模偏分桶桶位（(hash_u32*100)>>>32 ∈ [0,100)；HOLMES/降级场景照记随审计）';

-- ---------- 2. 唯一活跃索引升 (incident_id, engine) 粒度 ----------

drop index uq_rca_run_active_incident;
create unique index uq_rca_run_active_incident
    on rca_run(incident_id, engine) where state in ('QUEUED','RUNNING');

-- ---------- 3. canary_route_decision：路由决策 append-only 审计 ----------

create table canary_route_decision (
    id             bigserial primary key,
    run_id         uuid not null references rca_run (id),
    stickiness_key text,
    canary_bucket  integer,
    percent        integer not null check (percent between 0 and 100),
    bundle_digest  char(64),
    decision       text not null check (decision in (
                       'NO_ACTIVE_BUNDLE','CANARY_DISABLED','NO_STICKINESS_KEY',
                       'WHITELISTED','BUCKETED_NATIVE','BUCKETED_HOLMES',
                       'NATIVE_DEFERRED','BLAST_RADIUS_STOPPED')),
    created_at     timestamptz not null
);

comment on table canary_route_decision is
    'AM5 M5-10 Canary 路由决策审计（insert-only append-only；爆炸半径重数源 = decision ∈ WHITELISTED/BUCKETED_NATIVE 的计数，无状态重查自愈）';

-- ---------- 4. 授权（V7 惯例：审计表只 select,insert） ----------

grant select, insert on canary_route_decision to control_app;
revoke update, delete on canary_route_decision from control_app;
-- BA-42①（195 真 PG 实证：append 走 bigserial 默认值，表授权不覆盖序列面，
-- 漏 USAGE 即生产写路径 permission denied）
grant usage on sequence canary_route_decision_id_seq to control_app;
revoke all on sequence canary_route_decision_id_seq
    from publisher_app, notify_app, eval_app, public;

revoke all on canary_route_decision
    from publisher_app, notify_app, eval_app, public;
