-- CL-07 摘要控制（告警-Agent闭环修复 v1 §6.1/§6.4）：压缩尝试控制记录。
-- 短事务预留→外部模型调用→围栏事务终态：冻结来源/身份（owner/leaseEpoch/
-- configEpoch/expectedRevision），终态封闭集 RESERVED→IN_FLIGHT→
-- COMMITTED/REJECTED/FAILED/UNKNOWN/SUPERSEDED。任何拒绝/失败不静默删除记录。
-- 唯一键 = (task, source_context_digest, policy_digest, config_epoch)：
-- 同逻辑动作（同源+同策略+同配置代）一胜一拒；config_epoch 空值以 -1 入索引。
create table rca_compaction_attempt (
    id uuid primary key,
    run_id uuid not null,
    task_id uuid not null,
    source_context_digest char(64) not null,
    policy_digest char(64) not null,
    owner text,
    lease_epoch bigint,
    config_epoch bigint,
    expected_revision bigint not null,
    state text not null,
    logical_action_key text not null,
    error_code text,
    summary_id uuid,
    created_at timestamptz not null,
    settled_at timestamptz,
    constraint ck_rca_compaction_attempt_state check (state in
        ('RESERVED', 'IN_FLIGHT', 'COMMITTED', 'REJECTED', 'FAILED',
         'UNKNOWN', 'SUPERSEDED')),
    constraint ck_rca_compaction_attempt_settle check (
        (state in ('RESERVED', 'IN_FLIGHT')) and settled_at is null
        or state not in ('RESERVED', 'IN_FLIGHT') and settled_at is not null),
    constraint ck_rca_compaction_attempt_commit check (
        state <> 'COMMITTED' or summary_id is not null)
);

create unique index uq_rca_compaction_attempt on rca_compaction_attempt
    (task_id, source_context_digest, policy_digest, coalesce(config_epoch, -1));

create index idx_rca_compaction_attempt_run on rca_compaction_attempt (run_id);

grant select, insert, update on rca_compaction_attempt to control_app;
