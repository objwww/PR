-- ============================================================================
-- V97 —— EN-08 Skill 自沉淀链（候选生命周期台账）
--
-- rca_skill_candidate：Skill 候选生命周期行（§四：DRAFT→VALIDATING→EVALUATING→
-- QUALIFIED→ACTIVE→DEPRECATED→RETIRED；校验/评测失败保留 REJECTED 记录；
-- INCONCLUSIVE 属评测结论不能晋升 → 记 REJECTED+原因）。行可变（生命周期状态机
-- 面），历史不改写只前进（S12：普通退场与紧急撤销语义分开，行永不删=可恢复历史）。
--
-- 状态表达分工（§四"不强迫一张表塞全部枚举"）：生命周期推进在本表；评测证明在
-- release_qualification（PASS/FAIL/INCONCLUSIVE+撤销三件套）；SKILL 资产本体在
-- release_asset（SKILL kind，内容寻址=幂等注册锚+篡改新身份锚 S10）；发布组合
-- 允许集在 release_manifest.skills（EN-01）。本表不复制评测结论，只存推进门槛
-- 所需的指针与状态。
--
-- 幂等：uq(source_digest, name)——S14 重复提交生成作业返回既有行，不堆重复候选，
-- 生成成本以既有行 createdAt 审计（不丢账）。落 DRAFT 后崩溃恢复=同键重放读既有行。
--
-- S02 原料门：verification_status=UNVERIFIED 的提案落 REJECTED 行（隔离状态明确），
-- asset_digest 留空（不落资产）；VERIFIED 候选后续校验/评测失败转 REJECTED 时保留
-- DRAFT 资产指针（历史不改写只前进）。
-- 授权：control_app select,insert,update（生命周期推进）；publisher_app 零授权
-- （发布面归 release 管辖，候选域不越权）；eval_app 不授（评测证明写在
-- release_qualification，不在本表）。
-- 回滚：drop table rca_skill_candidate。
-- 号段：V96 已占（MC-P0 批），本卡按下一可用号 V97 落位。
-- ============================================================================

create table rca_skill_candidate (
    id                  uuid primary key,
    name                text not null,
    source_run_id       uuid not null,          -- 封存轨迹来源 run（原料溯源面）
    source_digest       char(64) not null,      -- 来源轨迹冻结 digest（R2 capture 面）
    verification_status text not null,          -- VERIFIED（有人工复核依据）/ UNVERIFIED
    asset_digest        char(64),               -- DRAFT 起 SKILL 资产指针（REJECTED 空）
    status              text not null,
    failure_reason      text,
    proposed_by         text not null,
    activated_by        text,
    activated_at        timestamptz,
    retired_by          text,
    retired_at          timestamptz,
    retire_reason       text,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,

    constraint uq_rca_skill_candidate_source unique (source_digest, name),
    constraint ck_rca_skill_candidate_status check (status in
        ('DRAFT','VALIDATING','EVALUATING','QUALIFIED','ACTIVE',
         'DEPRECATED','RETIRED','REJECTED')),
    constraint ck_rca_skill_candidate_verification
        check (verification_status in ('VERIFIED','UNVERIFIED')),
    constraint ck_rca_skill_candidate_asset
        check (verification_status <> 'UNVERIFIED'
               or (status = 'REJECTED' and asset_digest is null)),
    constraint ck_rca_skill_candidate_activation
        check (status <> 'ACTIVE' or (activated_by is not null
                                      and activated_at is not null)),
    constraint ck_rca_skill_candidate_retire
        check (status <> 'RETIRED' or (retired_by is not null
                                       and retired_at is not null
                                       and retire_reason is not null))
);

create index ix_rca_skill_candidate_status on rca_skill_candidate(status, name);

grant select, insert, update on rca_skill_candidate to control_app;
revoke all on rca_skill_candidate from publisher_app;
revoke all on rca_skill_candidate from public;

comment on table rca_skill_candidate is
    'EN-08 Skill 候选生命周期（生成器仅写 DRAFT；QUALIFIED 需 PASS 资格；ACTIVE 需人工授权；行永不删）';
