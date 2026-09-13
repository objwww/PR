-- OP-03 调查动作价值分析（后续优化技术方案 §4.1）：版本化派生台账，不改原始
-- 调用账本。按逻辑动作（task+tool+action_digest）聚合物理尝试，记录新增观察数
-- （同源同窗规范化内容去重——不以证据 UUID 不同当新观察，FO11）、报告引用数、
-- 可解释分类（NEW_OBSERVATION/CONFIRMS_OR_REFUTES/NO_DATA/DUPLICATE_SAME_
-- SNAPSHOT/SOURCE_FAILED/UNDETERMINED）与置信口径（首期确定性规则）。
-- 唯一键 (run, logical_action_key, assessor_version, evidence_snapshot_digest)：
-- 同版本同快照重入不重复（FO15）；迟到对账/报告变化→新快照或新版本重算。
create table rca_action_assessment (
    id uuid primary key,
    run_id uuid not null,
    task_id uuid not null,
    logical_action_key text not null,
    assessor_version text not null,
    evidence_snapshot_digest char(64) not null,
    physical_attempts int not null,
    new_observation_count int not null,
    gap_resolution_refs jsonb not null default '[]',
    report_citation_count int not null,
    classification text not null,
    confidence_kind text not null,
    computed_at timestamptz not null,
    constraint ck_rca_action_assessment_class check (classification in
        ('NEW_OBSERVATION', 'CONFIRMS_OR_REFUTES', 'NO_DATA',
         'DUPLICATE_SAME_SNAPSHOT', 'SOURCE_FAILED', 'UNDETERMINED'))
);

create unique index uq_rca_action_assessment on rca_action_assessment
    (run_id, logical_action_key, assessor_version, evidence_snapshot_digest);

create index ix_rca_action_assessment_run on rca_action_assessment (run_id, classification);

grant select, insert on rca_action_assessment to control_app;
