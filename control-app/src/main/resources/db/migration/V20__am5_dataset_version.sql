-- ============================================================================
-- V20 —— AM5 数据集版本底座（M5-01；docs/告警AM5-落码技术方案.md §M5-01②）
--   dataset_version  数据集版本头（insert-only）：来源九字段
--                    （source/name/version/source_uri/license/access_class/
--                    content_digest/adapter_version/imported_at，方案 §3.1 v1.1）
--                    + source_class 分级身份 + partition_class 四分区归属
--                    + scenario_family_digest（案例族清单摘要；
--                      整组分区键 scenario_family_id 列在 case 侧）
--   case_version     案例版本行（insert-only）：适用期 [valid_from, valid_to)
--                    + scenario_family_id 整组分区键 + payload(EvalCaseV1)
--                    + source_artifact_ref 原始 artifact 引用（不覆盖来源字段）
--
-- 编号说明：技术方案 v1.2 原文"AM5 自 V19 起"已过时——AM4 V19__am4_tool_replay
-- 占用（落码方案迁移表开工实测复核），一迁移一任务顺延为 V20 起（INV-AM5-10）。
--
-- 不可覆盖语义（M5-01 验收"历史不可覆盖"）：
--   - dataset_version 同 (name,version) 唯一，禁重生；
--   - case_version 同 (dataset_version_id,case_key) 唯一——纠错 = 导入新
--     dataset_version 携带修正行，历史版本行永不变更；
--   - 两表只授 eval_app select,insert——无任何 UPDATE/DELETE 授权（V10 惯例），
--     接口层（DatasetVersionRepository）无 UPDATE/DELETE 方法面。
--
-- scenario_family_id 整组分区纪律：同族禁止拆跨 TUNING/HOLDOUT 等分区，
-- 由 M5-01 应用层断言（DatasetAdapter.assertFamilyPartition）前置把关，
-- M5-02（V21）以 case_version 上 unique (scenario_family_id, partition_class)
-- DB 兜底；本迁移先固化族键非空与 partition_class 封闭值域。
--
-- 授权分工：eval_app = eval-runner 独立身份（M3-15/V10 惯例），生产
-- control_app / publisher_app / notify_app / public 一律零权限。
-- ============================================================================

-- ---------- 1. dataset_version：数据集版本头（insert-only） ----------

create table dataset_version (
    id                     uuid primary key,
    source                 text not null,        -- 来源系统（order-arena/rca100/rcaeval）
    name                   text not null,        -- 数据集名（导入 datasetRef 声明）
    version                text not null,        -- 外部版本声明（RCA-100 锚定 v1.1 禁 latest）
    source_uri             text not null,        -- 来源定位（RCA-100 固定版本 URI）
    license                text not null,        -- 许可（Redistribution 授权核查输入）
    access_class           text not null,        -- 访问级别（九字段之六）
    content_digest         char(64) not null,    -- manifest 摘要（datasetRef.manifestDigest，版本锚）
    adapter_version        text not null,        -- 适配器版本（转换面可追溯）
    imported_at            timestamptz not null, -- 导入时刻
    source_class           varchar(24) not null, -- 分级身份（INV-AM5-1：公共集不冒充私有 HOLDOUT）
    partition_class        varchar(16) not null, -- 四分区归属（M5-02 RLS 权限矩阵的行侧输入）
    scenario_family_digest char(64) not null,    -- 本版本案例族清单摘要（族增删即换版本锚）
    created_at             timestamptz not null default now(),

    constraint uq_dataset_version unique (name, version),
    constraint ck_dataset_version_source_class
        check (source_class in ('PRIVATE','PUBLIC_BENCHMARK')),
    constraint ck_dataset_version_partition_class
        check (partition_class in ('TUNING','VALIDATION','HOLDOUT','REDTEAM'))
);

comment on table dataset_version is
    'AM5 数据集版本头（M5-01，insert-only）：来源九字段 + 分级/分区身份，历史不可覆盖';
comment on column dataset_version.partition_class is
    '四分区归属：TUNING/VALIDATION/HOLDOUT/REDTEAM；同族整组不拆分（应用层断言 + V21 DB 兜底）';
comment on column dataset_version.scenario_family_digest is
    '本版本案例族清单摘要（去重排序族键规范串的 SHA-256）';

-- ---------- 2. case_version：案例版本行（insert-only + 适用期） ----------

create table case_version (
    id                  uuid primary key,
    dataset_version_id  uuid not null references dataset_version(id),
    case_key            text not null,      -- 数据集内稳定案例标识
    scenario_family_id  text not null,      -- 整组分区键（同族禁拆跨分区）
    valid_from          timestamptz not null,
    valid_to            timestamptz,        -- null = 开放期（现行版本）；[valid_from, valid_to) 半开
    content_digest      char(64) not null,  -- 案例内容摘要（canonical 形态由导入方计算）
    payload             jsonb not null,     -- EvalCaseV1 序列化（期望根因/症状码/原始 artifact）
    source_artifact_ref text,               -- 原始 artifact 引用指针（不覆盖来源九字段）
    created_at          timestamptz not null default now(),

    constraint uq_case_version unique (dataset_version_id, case_key),
    constraint ck_case_version_validity check (valid_to is null or valid_to > valid_from)
);

comment on table case_version is
    'AM5 案例版本行（M5-01，insert-only）：同 (dataset_version_id,case_key) 唯一，纠错 = 新 dataset_version 携带修正行，历史版本行永不变更';
comment on column case_version.source_artifact_ref is
    '原始 artifact 引用指针（Parquet/JSON 定位），与 payload 内原始证据互为表里';

-- ---------- 3. 授权：eval_app 只读+插；生产角色全部零权限（V10 同构） ----------

grant select, insert on dataset_version, case_version to eval_app;

revoke all on dataset_version, case_version
    from control_app, publisher_app, notify_app, public;
-- arena 域角色在 control-only 干净库（IT 的 Testcontainers 库）可能不存在——
-- 条件化（幂等；真实部署由 01-roles.sh 创建），V10 同构
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on dataset_version, case_version from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on dataset_version, case_version from chaos_admin_app;
    end if;
end
$$;
