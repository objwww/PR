-- ============================================================================
-- V8 —— AM1 v2.2 结构预留增量（G0-08；来源：AM1 方案 v2.2 + 告警G0-收口技术方案）
--   rca_task_edge          多 Agent 任务 DAG 边表（架构 AA-15；AM1 空表预留，零写入）
--   rca_task 预留列        task_type/agent_profile/observed_generation/input_digest/
--                          output_artifact_ref/optional/max_total_duration/schema_version
--                          （四契约版本化的 task 侧落点，架构 AA-16）
--   rca_report 状态链      validation_status CHECK 扩为 AA-16 全链；既有 REJECTED_*
--                          细分值保留（结构验证链当前落点，AM1 只写到 STRUCTURE_VALIDATED）
--   scheduler_slot.task_id V7 已含（本迁移核对结论，无需回填）
--   授权矩阵沿用 V7 惯例：edge 只增不改（select,insert）；publisher_app 显式 REVOKE；PUBLIC 零权限
--   注意：本文件不是 AM3 的 eval/notify 迁移——那两个已顺延为 V9（G0 迁移编号裁定）
-- ============================================================================

-- ---------- 1. rca_task_edge：任务 DAG 边（AM1 空表预留） ----------

create table rca_task_edge (
    id              uuid primary key,
    run_id          uuid not null references rca_run(id),
    from_task_id    uuid not null references rca_task(id),
    to_task_id      uuid not null references rca_task(id),
    dependency_type varchar(16) not null default 'REQUIRED',   -- AA-15：REQUIRED/OPTIONAL
    created_at      timestamptz not null default now(),

    constraint uq_rca_task_edge unique (from_task_id, to_task_id),

    constraint ck_rca_task_edge_dep
        check (dependency_type in ('REQUIRED','OPTIONAL')),

    constraint ck_rca_task_edge_no_self
        check (from_task_id <> to_task_id)
);

create index ix_rca_task_edge_run on rca_task_edge(run_id);

-- ---------- 2. rca_task 预留列（AA-15/AA-16/AA-21；AM1 不写入，默认值即语义） ----------

alter table rca_task
    add column task_type           varchar(64),                 -- 预留：HOLMES_INVESTIGATE 之外的任务类型注册表键
    add column agent_profile       text,                        -- 预留：执行体画像（模型路由/工具集标识）
    add column observed_generation integer,                     -- 预留：铸造时观察到的 incident.generation（Claim 新鲜度，AA-17）
    add column input_digest        char(64),                    -- 预留：任务输入材料摘要（幂等键成分，AA-21）
    add column output_artifact_ref text,                        -- 预留：产出 artifact 引用（CAS digest，AA-24）
    add column optional            boolean not null default false,  -- 预留：可选任务（失败不阻断下游）
    add column max_total_duration  interval,                    -- 预留：任务总时限（含重试）
    add column schema_version      integer not null default 1;  -- 预留：任务契约版本（四契约版本化，AA-16）

-- ---------- 3. rca_report 状态链 CHECK 扩全链（AA-16） ----------

alter table rca_report
    drop constraint ck_rca_report_status;

alter table rca_report
    add constraint ck_rca_report_status
        check (validation_status in (
            -- AA-16 全链：DRAFT→STRUCTURE_VALIDATED→EVIDENCE_VALIDATED→PUBLISHED；
            -- 失败分支 REJECTED/NEEDS_REVIEW/SUPERSEDED（AM1 只写到 STRUCTURE_VALIDATED）
            'DRAFT', 'STRUCTURE_VALIDATED', 'EVIDENCE_VALIDATED',
            'NEEDS_REVIEW', 'PUBLISHED', 'REJECTED', 'SUPERSEDED',
            -- 既有结构验证链细分值（AM1 EvidencePackageValidator 当前落点，保留）
            'REJECTED_MALFORMED', 'REJECTED_OVERSIZE',
            'REJECTED_SCHEMA_VERSION', 'REJECTED_SCHEMA_MISMATCH'));

-- ---------- 4. 授权（V7 惯例：edge 只增不改；表级 grant 自动覆盖 rca_task 新列） ----------

grant select, insert on rca_task_edge to control_app;

revoke all on rca_task_edge from publisher_app;
revoke all on rca_task_edge from public;
