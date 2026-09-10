-- ============================================================================
-- V46 —— R7-X1 持久角色绑定（告警R7-真LLM多Agent技术方案 v2.1 §十一.2/§十三卡 R7-X1）
--
-- 1) rca_task 增加 round_id（主 Agent 委派/补证 round 身份，§四 初始 round0）：
--    taskKey 回归纯业务实例标识（§十一.2 "原 taskKey 仅为本轮业务实例标识，不再
--    承担角色身份"），唯一键由 (run_id, task_key) 升 (run_id, round_id, task_key)——
--    同 taskKey 跨轮独立（RX07），同轮重复提交撞唯一键（幂等由 Supervisor 启动短路
--    兜底）。存量行 default 0 = round 0。
-- 2) rca_task_execution_binding：任务→角色的冻结绑定（§十一.2 任务绑定清单：
--    task_id/run_id/round_id/role_id/version/digest/release_digest/config_epoch/
--    input_refs/expected_output_schema/parent_request_id/required/失败策略）。
--    编译事务写入，恢复只读本表+固定资产，不猜 latest、同名版本不串用；
--    已部署节点无对应运行器 → CAPABILITY_UNAVAILABLE（§十一.2 恢复纪律）。
--    绑定=冻结事实：只增不改（同 alert_event 授权惯例）。
-- 回滚：drop 表 + 还原唯一键 + drop 列（先滚应用后滚库）。
-- 编号纪律：rebase 时以下一可用号为准替换 V46。
-- ============================================================================

alter table rca_task
    add column round_id integer not null default 0;

alter table rca_task
    add constraint ck_rca_task_round check (round_id >= 0);

alter table rca_task
    drop constraint uq_rca_task_key;

alter table rca_task
    add constraint uq_rca_task_key unique (run_id, round_id, task_key);

create table rca_task_execution_binding (
    task_id                 uuid primary key references rca_task(id),
    run_id                  uuid not null references rca_run(id),
    round_id                integer not null default 0,
    task_key                text not null,           -- 本轮业务实例标识（非角色身份）
    role_id                 text not null,
    role_version            text not null,
    role_digest             char(64) not null,       -- 编译期钉死的 Profile digest
    release_digest          char(64),                -- 路由 bundle digest（影子 run 可缺）
    config_epoch            bigint,                  -- 配置代际（首期无源，留白）
    input_refs              jsonb not null,          -- 编译期校验过的本 run artifact 引用
    expected_output_schema  jsonb not null,          -- 绑定时刻 Profile 输出 schema 冻结件
    parent_request_id       uuid,                    -- 委派请求锚（X4 起接源，round0 主任务恒 null）
    required                boolean not null default true,
    failure_policy          text not null default 'DEAD_ON_FAILURE',
    created_at              timestamptz not null,

    constraint uq_rca_task_binding_key unique (run_id, round_id, task_key),
    constraint ck_rca_task_binding_round check (round_id >= 0),
    constraint ck_rca_task_binding_failure_policy
        check (failure_policy in ('DEAD_ON_FAILURE', 'SKIP_WITH_REASON'))
);

create index ix_rca_task_binding_run on rca_task_execution_binding(run_id, round_id);

comment on table rca_task_execution_binding is
    'R7-X1 任务→角色冻结绑定（编译事务写入/恢复只读不猜 latest/缺运行器 CAPABILITY_UNAVAILABLE）';

-- 授权（V7 同构）：绑定=冻结事实，只增不改；publisher_app/public 显式冻结
grant select, insert on rca_task_execution_binding to control_app;
revoke all on rca_task_execution_binding from publisher_app;
revoke all on rca_task_execution_binding from public;
