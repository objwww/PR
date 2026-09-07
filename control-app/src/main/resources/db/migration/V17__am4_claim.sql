-- ============================================================================
-- V17 —— AM4 Claim 双哈希投影（M4-21 同任务迁移；技术方案 v1.3 §6 Claim 与裁决条目）
--
--   双标识：claim_fingerprint = 身份（类型/键+scope+时间窗+generation+input snapshot，
--   与裁决分组键同集）；claim_hash = 内容（状态+原因+证据引用+来源+策略版本）。
--   四分支（语义在 ClaimProjection，仓储只执行）：fp 不存在 → INSERT；
--   fp 同+hash 同 → 幂等（只追加 CLAIM_UNCHANGED 审计事件）；
--   fp 同+hash 异 → CAS 更新当前投影（WHERE claim_hash=旧值）+ CLAIM_REVISED 事件
--   （载荷携带修订前内容——当前投影被更新，旧内容只活在事件账本）；
--   新 generation/scope → 新 fingerprint 落新行，旧记录只标 lifecycle=SUPERSEDED
--   （历史不可变：内容列永不改写，不采纳"证据撤销级联改写历史"）。
--   三正交字段 = status（TRUE/FALSE/UNKNOWN）+ evidence_basis（SINGLE_SOURCE/
--   MULTI_SOURCE_CONSISTENT/MULTI_SOURCE_CONFLICT）+ lifecycle（ACTIVE/SUPERSEDED）；
--   无独立 verdict 出口枚举。不建空 Claim 表达无结论（CLAIM_UNRESOLVED 只进事件账本，
--   本表无行）。sources/evidence_refs 为 jsonb 数组（内容已由 claim_hash 覆盖，
--   非字节回读面，无 TEXT canonical 要求）。
-- ============================================================================

create table rca_claim (
    id                  uuid         not null,
    run_id              uuid         not null,
    claim_fingerprint   varchar(64)  not null,
    claim_hash          varchar(64)  not null,
    claim_key           varchar(256) not null,
    status              varchar(8)   not null,
    evidence_basis      varchar(32)  not null,
    lifecycle           varchar(16)  not null default 'ACTIVE',
    reason              text         not null,
    scope               text         not null,
    time_range          text         not null,
    observed_generation bigint       not null,
    sources             jsonb        not null,
    evidence_refs       jsonb        not null,
    policy_version      varchar(64)  not null,
    snapshot_digest     varchar(64),
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),
    constraint pk_rca_claim primary key (id),
    constraint fk_rca_claim_run foreign key (run_id) references rca_run (id),
    constraint uq_rca_claim_fingerprint unique (run_id, claim_fingerprint),
    constraint ck_rca_claim_status
        check (status in ('TRUE', 'FALSE', 'UNKNOWN')),
    constraint ck_rca_claim_basis
        check (evidence_basis in ('SINGLE_SOURCE', 'MULTI_SOURCE_CONSISTENT',
                                  'MULTI_SOURCE_CONFLICT')),
    constraint ck_rca_claim_lifecycle check (lifecycle in ('ACTIVE', 'SUPERSEDED')),
    constraint ck_rca_claim_generation check (observed_generation >= 0)
);

-- supersede 面：同 proposition（key+scope+time_range）找更旧代际 ACTIVE 投影
create index ix_rca_claim_proposition on rca_claim (run_id, claim_key, scope, time_range);

comment on table rca_claim is
    'AM4 M4-21 断言投影（双哈希身份/内容；三正交字段；四分支写路径；历史不可变）';

grant select, insert, update on rca_claim to control_app;
