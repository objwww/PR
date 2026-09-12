-- ============================================================================
-- V96 —— MC21~23 子任务回执台账 + MC31/32 人工材料入口（修补方案 v1 批 P0）
--
-- 1) rca_delegation_receipt：委派子任务结果的<b>结构化回执台账</b>（R7 方案 §20.1
--    委派信封和结果合并 / §19.5 子任务结果契约 status/findings/supportRefs/
--    counterRefs/missingInformation）。MC21 三面：
--      · message_id 唯一 = 幂等准入键——同 messageId 重复投递恰一行，有效结果只
--        合入一次（合并面=本表 ACCEPTED 行，重复投递不产生第二行）；
--      · 超大回执显式拒绝：payload 超限落 OVERSIZED 审计行，findings 强制空
--        （有界载荷不落库，无内存失控），digest/字节数留痕可对账；
--      · 迟到/终态围栏：run 已离活跃集（终态/被新代际取代）后的回执 = LATE
--        审计行，不合入有效记忆不改终态（MC23：W1 终态后的迟到证据不冒充新
--        现场——run 域隔离 + 本围栏双保险）。
--    失败也必须返回结构化缺口：ACCEPTED + child_status=FAILED ⇒
--    missing_information 非空（ck 强制），不存在"空字符串失败"。
-- 2) incident_operator_material：人工补充材料入口（MC31/32，MA-07 人为补充
--    材料审计面）。身份由认证端铸定（operator 列=AuthenticatedActor，非客户端
--    自报）；kind 区分 OBSERVATION/EVIDENCE_LINK/JUDGMENT，EVIDENCE_LINK 必带
--    source_ref（材料有来源）；JUDGMENT 类不进 validRefs（无证判断不能绕过
--    Claim 准入，PrimaryClaimAdmission 面不变）。MC32 CAS：(incident_id,
--    revision) 唯一 = 材料集版本单调——两人同 base_revision 并发提交至多一个
--    生效，败方收 CONFLICT 可见；incident 已 RESOLVED 后提交 = REJECTED_LATE
--    明确终态（不无限挂起，不静默丢弃）。
-- 回滚：drop 两表（先滚应用后滚库）。
-- 编号纪律：rebase 时以下一可用号为准替换 V96。
-- ============================================================================

create table rca_delegation_receipt (
    id                uuid primary key,
    message_id        uuid not null unique,      -- MC21 幂等准入键（重投恰一行）
    run_id            uuid not null references rca_run(id),
    primary_task_id   uuid references rca_task(id), -- 可空=身份面拒绝审计行（对不上裁决，诚实态）；ACCEPTED 行必非空
    child_task_id     uuid not null references rca_task(id),
    round_id          integer not null,
    gap_id            text not null,
    role_id           text not null,
    child_status      text not null,             -- SUCCEEDED | FAILED
    admission         text not null,             -- ACCEPTED | OVERSIZED | LATE | REJECTED_SHAPE
    findings          jsonb not null default '[]',  -- ACCEPTED 时有界载荷；OVERSIZED 强制空
    support_refs      jsonb not null default '[]',
    counter_refs      jsonb not null default '[]',  -- MC22：反证可追溯（信封/记忆槽消费）
    missing_information jsonb not null default '[]',
    payload_digest    char(64) not null,         -- 有界载荷 sha256（OVERSIZED 也留痕可对账）
    payload_bytes     integer not null,
    received_at       timestamptz not null,

    constraint ck_mc21_receipt_child_status check (
        child_status in ('SUCCEEDED', 'FAILED')),
    constraint ck_mc21_receipt_admission check (
        admission in ('ACCEPTED', 'OVERSIZED', 'LATE', 'REJECTED_SHAPE')),
    constraint ck_mc21_receipt_oversized_bounded check (
        admission <> 'OVERSIZED' or findings = '[]'::jsonb),
    constraint ck_mc21_receipt_failed_gap check (
        admission <> 'ACCEPTED' or child_status <> 'FAILED'
            or missing_information <> '[]'::jsonb),
    constraint ck_mc21_receipt_payload_size check (
        payload_bytes >= 0 and payload_bytes <= 16777216
        and (admission <> 'ACCEPTED' or payload_bytes <= 65536)),
    constraint ck_mc21_receipt_round check (round_id >= 0)
);

create index ix_mc21_receipt_round on
    rca_delegation_receipt(run_id, primary_task_id, round_id);
create index ix_mc21_receipt_child on rca_delegation_receipt(child_task_id);

comment on table rca_delegation_receipt is
    'MC21~23 委派子任务回执台账（message_id 幂等准入/超大显式拒绝/迟到审计不合入；§20.1 结果合并契约）';
comment on column rca_delegation_receipt.message_id is
    '幂等准入键：同 messageId 重复投递恰一行，有效结果只合入一次（MC21）';
comment on column rca_delegation_receipt.admission is
    '准入裁决：ACCEPTED=合入合并面；OVERSIZED=超限审计（载荷不落库）；LATE=run 终态后迟到审计（MC23 不冒充新现场）；REJECTED_SHAPE=结构契约违约审计';

create table incident_operator_material (
    id              uuid primary key,
    incident_id     uuid not null references incident(id),
    run_id          uuid references rca_run(id),   -- 可空=事故级材料（无在飞调查）
    operator        text not null,                 -- 认证端身份（AuthenticatedActor），非客户端自报
    kind            text not null,                 -- OBSERVATION | EVIDENCE_LINK | JUDGMENT
    source_ref      text,                          -- EVIDENCE_LINK 必非空（真实变更链接等）
    content         text not null,
    base_revision   integer not null,              -- 提交者所见材料集版本（MC32 CAS 输入）
    revision        integer not null,              -- 生效后材料集版本（=base+1；唯一键=串行化点）
    admission       text not null,                 -- ACCEPTED | REJECTED_LATE（CAS 败方不落行，应答可见）
    created_at      timestamptz not null,

    constraint uq_mc32_material_revision unique (incident_id, revision),
    constraint ck_mc31_material_kind check (
        kind in ('OBSERVATION', 'EVIDENCE_LINK', 'JUDGMENT')),
    constraint ck_mc31_material_source check (
        kind <> 'EVIDENCE_LINK' or source_ref is not null),
    constraint ck_mc31_material_content check (
        char_length(content) between 1 and 20000),
    constraint ck_mc32_material_revision check (
        base_revision >= 0 and revision >= 1),
    constraint ck_mc31_material_admission check (
        admission in ('ACCEPTED', 'REJECTED_LATE'))
);

create index ix_mc31_material_incident on incident_operator_material(incident_id, revision);

comment on table incident_operator_material is
    'MC31/32 人工补充材料入口（认证端身份/来源与实测证据区分/CAS 至多一生效/RESOLVED 后 REJECTED_LATE 明确终态）';
comment on column incident_operator_material.kind is
    'OBSERVATION=操作者观察；EVIDENCE_LINK=带 source_ref 的变更/外链证据；JUDGMENT=无引用判断（信封单列标注，不进 validRefs，不绕过 Claim 准入）';
comment on column incident_operator_material.revision is
    'MC32 CAS：材料集版本（unique(incident_id,revision) 为并发串行化点；冲突应答携带当前版本可见）';

-- 授权（V7/V47 同构）：两表均 append-only 审计台账（只增不改）
grant select, insert on rca_delegation_receipt to control_app;
grant select, insert on incident_operator_material to control_app;
revoke all on rca_delegation_receipt from publisher_app;
revoke all on rca_delegation_receipt from public;
revoke all on incident_operator_material from publisher_app;
revoke all on incident_operator_material from public;
