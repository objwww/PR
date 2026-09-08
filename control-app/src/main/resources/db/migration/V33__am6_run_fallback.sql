-- V33（AM6 M6-04 落码方案 M6-04 节）：run_fallback 恰一次栅栏 + report_generation_winner
-- 单一发布赢家。号段勘误：落码方案原定 V32 号位——V32 已被 M6-02 engine_comparison
-- 占用（V31 被 BA-53 占用后整体顺延一号），详见 PROGRESS 2026-09-08 V 槽位勘误条目。
--
-- 语义（落码方案 + 技术方案 §4.1/4.2 + C-68）：
--   * run 级 fallback：NATIVE run 的安全/运行故障经 V33 恰一次铸 HOLMES RERUN；
--     取消/过期/人工终止/UNRESOLVED/低质量绝不触发（FUT-12 语义分歧不回退）。
--   * fallback 事件（fallback_of）只作审计副本，不承担唯一性——唯一性由
--     uq_rf_source (source_native_run_id) 表级唯一占位承担（C-68：事件不能承担幂等）。
--   * 同一事务内先占 fallback 资格（INSERT run_fallback）、再铸 Holmes run/task；
--     占位先行 ⇒ fallback_run_id 的 FK 必须 DEFERRABLE（BA-53 同律：生产顺序敏感
--     约束一律评估 DEFERRABLE，提交点检查保不变性不炸合法写入序）。
--   * depth <= 1：fallback run（HOLMES）不再进入 fallback 判定面——结构性封死，
--     check 约束兜底。
--   * 独立 fallback 预算：预算计数面 = run_fallback 时间窗行数（服务层裁定），
--     本表只供计数与审计，不承担预算语义。
--   * report_generation_winner：(incident_id, generation) 恰一份 READY
--     publication/outbox——先 INSERT 成功（CAS 赢家）才可发布；败者报告仍落档
--     rca_report（INV-AM3-7 诚实记账）但不发布不通知。

create table run_fallback (
    id                   bigserial primary key,
    source_native_run_id uuid        not null references rca_run(id),
    source_incident_id   uuid        not null references incident(id),
    generation           integer     not null,
    fallback_run_id      uuid        not null,
    depth                integer     not null default 1 check (depth <= 1),
    error_class          text        not null,
    created_at           timestamptz not null default now(),
    constraint uq_rf_source unique (source_native_run_id),
    -- 占位先行、铸 run 在后（同事务）⇒ 提交点检查（BA-53 同律）
    constraint fk_rf_fallback_run foreign key (fallback_run_id)
        references rca_run(id) deferrable initially deferred
);

-- 独立预算计数面（服务层 count 窗口查询）
create index ix_rf_created on run_fallback(created_at);

create table report_generation_winner (
    incident_id      uuid        not null references incident(id),
    generation       integer     not null,
    winner_report_id uuid        not null references rca_report(id),
    winner_run_id    uuid        not null references rca_run(id),
    decided_at       timestamptz not null default now(),
    primary key (incident_id, generation)
);

comment on table run_fallback is
    'AM6 M6-04 run 级 fallback 恰一次栅栏：source_native_run_id 唯一占位 = 幂等锚（C-68），同事务铸 HOLMES RERUN';
comment on column run_fallback.source_native_run_id is
    'fallback 源 NATIVE run（唯一占位；历史 canary 实跑即占住 fallback 资格）';
comment on column run_fallback.fallback_run_id is
    '同事务铸造的 HOLMES RERUN run（engine 列走 DB 默认 HOLMES，无路由语义）';
comment on column run_fallback.depth is
    'fallback 深度（恒 1；fallback run 是 HOLMES，结构性不再 fallback，check 兜底）';
comment on column run_fallback.error_class is
    '触发 fallback 的源 run 封闭错误类（安全/运行故障域；语义分歧域永不入表）';

comment on table report_generation_winner is
    'AM6 M6-04 发布赢家栅栏：(incident_id, generation) 恰一份 READY publication/outbox，先 INSERT 成功者胜（C-68）';
comment on column report_generation_winner.winner_report_id is
    '赢家的报告行（报告行两侧都落档，败者只是不发布）';
comment on column report_generation_winner.winner_run_id is
    '赢家的 run（Native 部分收尾与 Holmes fallback 竞争下的唯一发布者身份）';

-- ---------- 授权（V7/V25/V30/V32 惯例：insert-only + select；BA-42① 序列 USAGE） ----------

grant select, insert on run_fallback to control_app;
revoke update, delete on run_fallback from control_app;
revoke all on run_fallback from publisher_app, notify_app, eval_app, public;
grant usage on sequence run_fallback_id_seq to control_app;
revoke all on sequence run_fallback_id_seq
    from publisher_app, notify_app, eval_app, public;

grant select, insert on report_generation_winner to control_app;
revoke update, delete on report_generation_winner from control_app;
revoke all on report_generation_winner
    from publisher_app, notify_app, eval_app, public;
