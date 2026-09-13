-- OP-01 固定回归集准入（后续优化技术方案 §3.1/§5.2）：报告反馈→独立审核→
-- 回归候选台账。候选由已终态报告（可选挂反馈）提出，source_digest 冻结来源指纹
-- （源报告后续修订不静默改候选——FO28）；正式入集=独立审核 ACCEPTED 后经
-- materialize 写 DatasetVersion/CaseVersion（不可变版本，不另存可编辑答案表）。
-- 审核意见 append-only 且 (candidate, reviewer) 唯一；两名审核者结论相异 →
-- 候选 DISPUTED 进裁决/待补证（FO27 不采用最后写入自动胜出）。
create table rca_regression_candidate (
    id uuid primary key,
    source_run_id uuid not null,
    source_report_id uuid not null,
    source_feedback_id uuid,
    source_digest char(64) not null,
    case_key text not null,
    scenario_family_id text not null,
    state text not null,
    created_by text not null,
    created_at timestamptz not null,
    reviewed_by text,
    review_reason text,
    reviewed_at timestamptz,
    constraint ck_rca_regression_candidate_state check (state in
        ('PENDING_REVIEW', 'ACCEPTED', 'REJECTED', 'NEEDS_EVIDENCE', 'DISPUTED')),
    constraint ck_rca_regression_candidate_review check (
        (state = 'PENDING_REVIEW' and reviewed_by is null and reviewed_at is null)
        or (state <> 'PENDING_REVIEW' and reviewed_by is not null
            and reviewed_at is not null))
);

create unique index uq_rca_regression_candidate
    on rca_regression_candidate (source_digest, case_key);

create table rca_regression_review (
    id uuid primary key,
    candidate_id uuid not null,
    reviewer text not null,
    verdict text not null,
    reason text not null,
    created_at timestamptz not null,
    constraint ck_rca_regression_review_verdict check (verdict in
        ('ACCEPTED_FOR_CANDIDATE', 'REJECTED', 'NEEDS_EVIDENCE')),
    constraint uq_rca_regression_review unique (candidate_id, reviewer)
);

grant select, insert, update on rca_regression_candidate to control_app;
grant select, insert on rca_regression_review to control_app;
