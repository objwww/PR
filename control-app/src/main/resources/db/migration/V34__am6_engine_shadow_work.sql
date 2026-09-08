-- V34: AM6 M6-05 Holmes 只读对照期——可租约恢复的 Shadow 持久工作面（C-65）
--
--   holmes_shadow_work      抽样命中以确定性 shadow_key 入库；SKIP LOCKED 批量认领 +
--                           租约过期回收（进程崩溃后可恢复）+ lease_epoch CAS + 有界重试
--                           （attempts < max_attempts，耗尽 EXHAUSTED）；预算预留 = 入队即
--                           占预算账（与 fallback 预算分账）。
--
-- 与 V33 的授权差异（有意）：V33 两表是"栅栏/赢家"事实行，append-only 不可改写；
-- 本表是租约工作面——state/lease/attempts 的 UPDATE 是语义的一部分（认领/CAS/
-- 有界重试），DELETE 仍拒（工作历史不可抹）。
-- 编号说明：落码方案 §M6-05 记 V33，C-68 修正使 M6-04 占 V33，本表顺延 V34。

create table holmes_shadow_work (
    id               bigserial primary key,
    shadow_key       text        not null unique,   -- 确定性键：holmes-shadow:<native_run_id> / holmes-calib:<native_run_id>
    kind             text        not null default 'COMPARISON'
                                 check (kind in ('COMPARISON', 'CALIBRATION')),
    native_run_id    uuid        not null references rca_run(id),
    incident_id      uuid        not null references incident(id),
    generation       integer     not null,
    snapshot_digest  char(64)    not null,          -- 与生产 run 的 investigation_hash 同源（同快照对照）
    state            text        not null default 'QUEUED'
                                 check (state in ('QUEUED', 'LEASED', 'SUCCEEDED', 'FAILED', 'EXHAUSTED')),
    attempts         integer     not null default 0,
    max_attempts     integer     not null default 3,
    lease_owner      text,
    lease_until      timestamptz,
    lease_epoch      integer     not null default 0,
    tokens_spent     integer,
    last_error       text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),

    constraint ck_hsw_generation check (generation >= 0),
    constraint ck_hsw_attempts check (attempts >= 0)
);

-- 认领序（先到先服务）与过期租约回收探测面
create index ix_hsw_claim on holmes_shadow_work (state, created_at);

grant select, insert, update on holmes_shadow_work to control_app;
grant usage on sequence holmes_shadow_work_id_seq to control_app;
revoke delete on holmes_shadow_work from control_app;
revoke all on holmes_shadow_work from publisher_app, notify_app, eval_app, public;

comment on table holmes_shadow_work is
    'M6-05 Holmes 只读对照期持久工作面：不铸生产 run 不调 finishTask（C-65），'
    '成功才恰一次落 engine_comparison（uq_ec_pair）；CALIBRATION 行=同快照 '
    'Holmes control-vs-control 底噪校准，结论落 engine_comparison.noise_baseline';
