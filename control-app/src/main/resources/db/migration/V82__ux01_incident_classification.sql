-- ============================================================================
-- V82 —— UX-01 告警分类（docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md §六、§七 UX-01 卡）
--
-- 分类体系：7 个业务类（BUSINESS/APPLICATION/DEPENDENCY/INFRA/NETWORK/DATA/SECURITY）
--   + PLATFORM（控制面自身）与 UNCLASSIFIED（未分类）两个独立出口——PLATFORM/UNCLASSIFIED
--   不混入 7 类概率排序，无任何置信度列（规则命中只留 ruleId/ruleVersion/命中依据，
--   不展示未经校准的概率数字）。
--
-- incident 新增两族列（人工 override 不覆盖规则结果——§六"规则重算不覆盖人工值"）：
--   rule_*       规则分类器的当前裁决（category_rule_id/version + classified_at 保可追溯）
--   override_*   人工修正（actor/reason/at + override_revision 乐观并发锚）
--   category / category_source 为 STORED 生成列——生效面唯一真源：
--     category = coalesce(override_category, rule_category, 'UNCLASSIFIED')
--     category_source = OVERRIDE（有 override）/ RULE
--   存量行 rule_category=NULL → 生效面 UNCLASSIFIED（不回填、不改写历史，§六）；
--   下一条非重复且在 episode 水印内的事件触发规则重分类（IncidentProjector）。
--
-- incident_category_override：人工修正审计表，insert-only（只授 INSERT+SELECT），
--   SET/REVOKE 两动作，谁/何时/从哪类到哪类/理由必填/期望与结果 revision/幂等键。
--
-- 授权：incident 表级 grant 及于后增列（V80 注释同律），control_app/eval_app 既有
--   读写面不变；审计表 control_app 只增读、eval_app 与 publisher/notify 显式 revoke
--   （eval_app 不写告警域，V10/V45 防漂移惯例）；PUBLIC 零权限。
-- ============================================================================

-- ---------- 1. incident 分类列 ----------

alter table incident
    add column rule_category           text,
    add column category_rule_id        text,
    add column category_rule_version   text,
    add column category_classified_at  timestamptz,
    add column override_category       text,
    add column override_actor          text,
    add column override_reason         text,
    add column override_at             timestamptz,
    add column override_revision       integer not null default 0,
    add column category                text generated always as
        (coalesce(override_category, rule_category, 'UNCLASSIFIED')) stored,
    add column category_source         text generated always as
        (case when override_category is not null then 'OVERRIDE' else 'RULE' end) stored;

-- 词表 CHECK 与代码枚举 IncidentCategory 一一对应（改词表 = 改枚举 + 新迁移）
alter table incident
    add constraint ck_incident_rule_category
        check (rule_category is null or rule_category in
            ('BUSINESS','APPLICATION','DEPENDENCY','INFRA','NETWORK','DATA','SECURITY',
             'PLATFORM','UNCLASSIFIED')),
    add constraint ck_incident_override_category
        check (override_category is null or override_category in
            ('BUSINESS','APPLICATION','DEPENDENCY','INFRA','NETWORK','DATA','SECURITY',
             'PLATFORM','UNCLASSIFIED')),
    -- 规则四列同生同灭：分类器恒产出 fallback（UNCLASSIFIED 也带 ruleId），
    -- 部分填写 = 写入面 bug，DB 直接拒
    add constraint ck_incident_rule_classification_whole
        check ((rule_category is null)
               = (category_rule_id is null and category_rule_version is null
                  and category_classified_at is null)),
    -- override 四列同生同灭；理由必填且非空白
    add constraint ck_incident_override_whole
        check ((override_category is null)
               = (override_actor is null and override_reason is null
                  and override_at is null)),
    add constraint ck_incident_override_reason
        check (override_reason is null or length(btrim(override_reason)) > 0),
    add constraint ck_incident_override_revision
        check (override_revision >= 0);

-- 分类过滤/facet 的扫描面（生成列可索引）
create index ix_incident_category on incident(category);

-- ---------- 2. incident_category_override：人工修正审计（insert-only） ----------

create table incident_category_override (
    id                 uuid primary key,
    incident_id        uuid not null references incident(id),

    action             varchar(8) not null,        -- SET=确立/改判 override；REVOKE=显式撤销
    from_category      text,                       -- 动作前生效面分类（审计快照）
    to_category        text,                       -- SET: 新 override 值；REVOKE: null（回落规则面）

    actor              text not null,              -- 认证主体（不采信自报）
    reason             text not null,              -- 理由必填（非空白）

    expected_revision  integer not null,           -- 乐观并发锚（动作前 override_revision）
    result_revision    integer not null,           -- 动作后 override_revision
    idempotency_key    text not null,              -- 幂等锚：同 (incident_id, key) 重放返回原行

    created_at         timestamptz not null,

    constraint ck_oco_action check (action in ('SET','REVOKE')),
    constraint ck_oco_from_category
        check (from_category is null or from_category in
            ('BUSINESS','APPLICATION','DEPENDENCY','INFRA','NETWORK','DATA','SECURITY',
             'PLATFORM','UNCLASSIFIED')),
    constraint ck_oco_to_category
        check (to_category is null or to_category in
            ('BUSINESS','APPLICATION','DEPENDENCY','INFRA','NETWORK','DATA','SECURITY',
             'PLATFORM','UNCLASSIFIED')),
    constraint ck_oco_set_has_target
        check (action <> 'SET' or to_category is not null),
    constraint ck_oco_revoke_no_target
        check (action <> 'REVOKE' or to_category is null),
    constraint ck_oco_reason check (length(btrim(reason)) > 0),
    constraint ck_oco_revisions
        check (expected_revision >= 0 and result_revision > expected_revision),
    constraint uq_oco_idempotency unique (incident_id, idempotency_key)
);

create index ix_oco_incident on incident_category_override(incident_id, created_at desc);

-- ---------- 3. 授权（V10/V45/V80 同构） ----------

-- control_app：override 命令面（行锁 + override_* 列写）沿 incident 表级 grant 自动及于
--   新列（V80 注释同律：PG 表级 grant 及于后增列），本迁移不重复授；
--   审计表只增读——零 UPDATE/DELETE 开口（insert-only）。
grant select, insert on incident_category_override to control_app;

-- 显式冻结（防未来 grant all 漂移）：eval_app 不写告警域（V11 仅持 incident SELECT，
--   分类新列可读无密）；publisher/notify/PUBLIC 归零。
revoke all on incident_category_override from publisher_app, notify_app, eval_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on incident_category_override from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on incident_category_override from chaos_admin_app;
    end if;
end
$$;
