-- ============================================================================
-- V47 —— R7-X4 主任务状态机检查点 + 委派裁决（v2.1 §四/§十三卡 R7-X4/R7-X11）
--
-- 1) rca_primary_checkpoint：主任务执行状态持久检查点（§四 必持久清单）。
--    主 Runner 无状态、单步推进（R7-X2 通用运行器），全部推进状态落本表——
--    任意一步崩溃后重驱动可从检查点续走（RD09）。phase 只有两态：
--    PRIMARY_READY（可推进）/ WAITING_CHILDREN（等子任务收官，不持任何锁等待，
--    由确定性 Supervisor 唤醒）。decision_seq/steps_used/batches_used 是 §三/§四
--    上限（max_steps/max_delegation_batches）的持久计数面；FINAL 分支把
--    claims/missing_information 先落检查点再进报告相位（§四 持久纪律）。
--    task_id 主键 = 一主任务一检查点（幂等 upsert 锚）。
-- 2) rca_delegation_decision：委派请求的确定性裁决台账（§三 Supervisor 校验
--    目录/权限/预算/去重/上限后 APPROVED 建子任务，违规 REJECTED 带原因）。
--    (run_id, gap_id) 唯一 = 同一信息缺口全 run 只裁一次（去重面，重驱动
--    重放同 gap 撞唯一键走幂等短路）；(run_id, primary_task_id, seq) 唯一 =
--    决策步序号稳定。child_task_id 裁决通过后回填。
--    裁决=既成事实：只增不改（child_task_id 回填除外，用 update 限定单列）。
-- 回滚：drop 两表（先滚应用后滚库）。
-- 编号纪律：rebase 时以下一可用号为准替换 V47。
-- ============================================================================

create table rca_primary_checkpoint (
    task_id                  uuid primary key references rca_task(id),
    run_id                   uuid not null references rca_run(id),
    round_id                 integer not null default 0,
    phase                    text not null,
    decision_seq             integer not null default 0,   -- 已裁决决策数（下一 seq = +1）
    steps_used               integer not null default 0,   -- 已耗主步数（对照 max_steps）
    batches_used             integer not null default 0,   -- 已耗委派批数（对照 max_delegation_batches=2）
    input_snapshot_digest    char(64),                     -- 快照冻结后回填（§五 快照先于调查）
    final_claims             jsonb,                        -- FINAL 分支 Claim 提案（报告相位准入用）
    final_missing_information jsonb,                       -- FINAL 分支信息缺口清单
    updated_at               timestamptz not null,

    constraint ck_r7_phase check (phase in ('PRIMARY_READY', 'WAITING_CHILDREN')),
    constraint ck_r7_seq_nonneg check (decision_seq >= 0 and steps_used >= 0
        and batches_used >= 0),
    constraint ck_r7_round_nonneg check (round_id >= 0)
);

create index ix_r7_checkpoint_run on rca_primary_checkpoint(run_id);

comment on table rca_primary_checkpoint is
    'R7-X4 主任务检查点（Runner 无状态单步推进/崩溃重驱动从此续走；§四 必持久清单）';

create table rca_delegation_decision (
    id                uuid primary key,
    run_id            uuid not null references rca_run(id),
    primary_task_id   uuid not null references rca_task(id),
    round_id          integer not null,
    seq               integer not null,          -- 决策步内请求序（全批统一 batch 维度见 batches_used）
    gap_id            text not null,
    role_id           text not null,
    role_version      text not null,
    question          text not null,
    status            text not null,
    reject_reason     text,                      -- APPROVED 必 null；REJECTED 必非空
    child_task_id     uuid,                      -- APPROVED 后回填（rca_task.id）
    created_at        timestamptz not null,

    constraint uq_r7_delegation_gap unique (run_id, gap_id),
    constraint uq_r7_delegation_seq unique (run_id, primary_task_id, round_id, seq),
    constraint ck_r7_delegation_status check (status in ('APPROVED', 'REJECTED')),
    constraint ck_r7_delegation_round check (round_id >= 0 and seq >= 0)
);

create index ix_r7_delegation_task on
    rca_delegation_decision(run_id, primary_task_id, round_id);

comment on table rca_delegation_decision is
    'R7-X4/X11 委派裁决台账（Supervisor 确定性校验后 APPROVED 建子/REJECTED 带因；同 gap 全 run 唯一）';

-- 授权（V7/V46 同构）：检查点可变（update）；裁决台账只增 + 单列回填
grant select, insert, update on rca_primary_checkpoint to control_app;
grant select, insert, update on rca_delegation_decision to control_app;
revoke all on rca_primary_checkpoint from publisher_app;
revoke all on rca_primary_checkpoint from public;
revoke all on rca_delegation_decision from publisher_app;
revoke all on rca_delegation_decision from public;
