-- SR-01/02（方案 docs/告警-Run停滞对账与影子执行收口方案-v1.md §3.1/§4.1）：
-- Run 身份三列（purpose/purpose_source/completion_kind）+ 对账持久面三列
-- （reconcile_deadline_at/reporting_started_at/recovery_attempts）。
--
-- 历史行保持 NULL purpose——读侧 coalesce 为 'LEGACY_UNKNOWN'（历史来源无法证明时
-- 不得标 PRODUCTION，禁止凭名字推断，方案 §3.1）；不做回填 UPDATE（迁移只追加，
-- 旧 Run 无可信 deadline 时对账只 ALERT_ONLY，不用当前配置追溯制造过期证据 §4.1）。
--
-- 活跃唯一索引 uq_rca_run_active_incident（V25）谓词不动：生产 REPORTING 的活跃
-- 并发保护保留（方案 §3.2——不从索引删除 REPORTING 绕开阻塞）。

alter table rca_run
    add column purpose varchar(16),
    add column purpose_source varchar(64),
    add column completion_kind varchar(32),
    add column reconcile_deadline_at timestamptz,
    add column reporting_started_at timestamptz,
    add column recovery_attempts int not null default 0;

alter table rca_run
    add constraint ck_rca_run_purpose
        check (purpose in ('PRODUCTION', 'SHADOW', 'EVAL', 'LEGACY_UNKNOWN'));

alter table rca_run
    add constraint ck_rca_run_completion_kind
        check (completion_kind is null
            or completion_kind in ('SHADOW_EVIDENCE_ONLY', 'QUEUE_DEADLINE', 'DEADLINE_EXPIRED'));

-- 对账候选批扫描（§4.1：有索引、有批上限；谓词与 isActive()/V12 uq 索引同集）
create index ix_rca_run_active_reconcile
    on rca_run (created_at)
    where state in ('QUEUED', 'RUNNING', 'REPORTING');
