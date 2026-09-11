-- ============================================================================
-- V61 —— EN-02 发布资格（release 域；增强线 Tool-MCP-RAG-Skill v1.1 §8.2 评测证明契约）
--   release_qualification  候选组合的评测证明行：候选/基线 digest、dataset manifest、
--                          runner/grader 版本、质量判定、费用对账状态、允许发布范围、
--                          审批/撤销记录。MATCHED 只用于费用对账语义，不能代替质量
--                          PASS（E05/S09）——FAIL+MATCHED 行合法存在但门永不通过。
--                          撤销 = 仅写 revoked_* 三列（内容列零改写；不授 delete）。
--
-- 门语义：activate/rollback 目标必须存在未撤销且 quality_verdict='PASS' 的证明；
-- 重验与指针 CAS 同事务（P07 事务内拒绝陈旧资格，实现侧 FOR UPDATE 串行化）。
--
-- 号段：EN=V60 起（2026-09-11 三线定死，增强线方案 §6.1）。
-- ============================================================================

create table release_qualification (
    id                       uuid primary key,
    candidate_digest         char(64) not null references config_bundle (bundle_digest),
    baseline_digest          char(64) references config_bundle (bundle_digest),
    dataset_manifest_digest  char(64) not null,
    runner_version           text not null,
    grader_version           text not null,
    quality_verdict          text not null
        check (quality_verdict in ('PASS', 'FAIL', 'INCONCLUSIVE')),
    usage_status             text not null
        check (usage_status in ('MATCHED', 'MISMATCH', 'UNKNOWN')),
    granted_scope            text not null,
    granted_by               text not null,
    granted_at               timestamptz not null,
    revoked_at               timestamptz,
    revoked_by               text,
    revoked_reason           text,
    constraint ck_release_qualification_revoke
        check (revoked_at is null or (revoked_by is not null and revoked_reason is not null))
);

create index idx_release_qualification_candidate
    on release_qualification (candidate_digest);

comment on table release_qualification is
    'EN-02 发布资格：独立评测证明（候选/基线/dataset/runner/grader/verdict/usage/scope/审批撤销）；MATCHED 不代替质量 PASS；activate/rollback 的资格门数据面';
comment on column release_qualification.usage_status is
    '费用对账语义（MATCHED/MISMATCH/UNKNOWN），不参与资格门判定（E05：MATCHED+根因错误=FAIL 不出合格证明）';

-- ---------- 授权（V24 惯例；撤销 = UPDATE 三列，delete 零授） ----------

grant select, insert, update on release_qualification to control_app;
revoke delete on release_qualification from control_app;

revoke all on release_qualification
    from publisher_app, notify_app, eval_app, public;
