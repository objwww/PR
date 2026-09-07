-- ============================================================================
-- V21 —— AM5 数据集四分区权限（M5-02；docs/告警AM5-落码技术方案.md §M5-02②）
--   case_version.partition_class  分区冗余直挂（V9 FUT-50 惯例：禁 JOIN 推导），
--                                 复合 FK 对 dataset_version(id,partition_class)
--                                 保冗余列与头表一致
--   case_family_partition         family 单分区登记面（insert-only）——
--                                 家族整组分区纪律的 DB 兜底（见下方 C-6 说明）
--   RLS + 四分区角色              case_version 行级安全：评分身份封存门前不见
--                                 HOLDOUT；四分区角色各见单分区；Agent/RAG/
--                                 调优界面身份（control_app 族）零策略 = 默认拒绝
--   trg_dataset_benchmark_not_holdout
--                                 INV-AM5-1 DB 兜底：公共 benchmark 不冒充私有
--                                 HOLDOUT（PRIVATE 之外的 source_class × HOLDOUT
--                                 组合直接拒绝）
--
-- 编号：落码方案迁移表 V21（一迁移一任务；已发布迁移不追加，INV-AM5-10）。
--
-- C-6 决策（偏离落码方案字面，保留其不变量）：§M5-02② 原文"case_version 上
--   unique (scenario_family_id, partition_class) 兜底"不可实施——①同族多 Case 是
--   设计常态（V20：同一故障注入模板的重复 Case 同族），(family,partition) 在行面
--   天然多行；②纠错 = 新 dataset_version 携带修正行（§M5-01② 自定语义），跨版本
--   同分区同族再次多行。故以登记面表 case_family_partition（PK(scenario_family_id)）
--   表达同一不变量"family → 至多一个 partition"：同族二次登记不同分区 =
--   DuplicateKey 拒绝；应用层前置门为 M5-01 DatasetAdapter.assertFamilyPartition。
--
-- RLS 设计（落码建议采纳：RLS + 分区角色）：
--   - RLS 对非表主非 BYPASSRLS 角色生效；迁移 owner（admin）不受限——评测查询
--     一律走受限身份（eval_app / 四分区角色），与 V3"评分走 eval_app"同纪律；
--   - eval_app（SCORING，评分身份）：select/insert 策略同谓词 partition_class <>
--     'HOLDOUT'——封存门前不可见也不可写 HOLDOUT（写路径 RLS 无策略即默认拒绝，
--     显式 with check 留下明确报错面）；
--   - 四分区角色（NOLOGIN 授权目标，V9 条件化建角色同构）：各见单分区；
--   - control_app / notify_app / arena 族（Agent/RAG/调优界面身份）：零策略 =
--     RLS 默认拒绝，即使未来误 grant select 也恒 0 行（纵深第二层；
--     第一层是 EvalIdentityGuard 应用侧矩阵）。
--
-- GT 延迟授权沿用（落码方案 §M5-02②"GT 列沿用 V11 延迟授权"）：arena GT 三条件
-- 门禁（V3：eval_app 评分身份专属 select，control/publisher/public 零权限）不在
-- 本迁移触碰范围。
--
-- 空表假设：AM5 未上线，case_version 加 NOT NULL 列无回填面；若非空会 fail-loud
-- （阻止静默错分区），届时须走显式回填迁移评审。
-- ============================================================================

-- ---------- 1. case_version.partition_class 冗余直挂 + 完整性 ----------

alter table case_version add column partition_class varchar(16) not null;

comment on column case_version.partition_class is
    '四分区归属（自 dataset_version 冗余直挂，写入面 INSERT..SELECT 派生）；'
    || 'RLS 与家族整组分区兜底的行侧谓词';

alter table dataset_version
    add constraint uq_dataset_version_partition unique (id, partition_class);

-- 单列 FK 由复合 FK 取代（dataset 存在性 + 分区一致性一并锁定）
alter table case_version drop constraint case_version_dataset_version_id_fkey;

alter table case_version
    add constraint ck_case_version_partition check (partition_class in
        ('TUNING','VALIDATION','HOLDOUT','REDTEAM'));

alter table case_version
    add constraint fk_case_version_dataset_partition foreign key
        (dataset_version_id, partition_class) references dataset_version (id, partition_class);

-- ---------- 2. case_family_partition：family 单分区登记面（insert-only） ----------

create table case_family_partition (
    scenario_family_id text primary key,      -- 家族整组分区键：一族群恰一行登记
    partition_class    varchar(16) not null,  -- 该族群唯一可落分区
    registered_at      timestamptz not null default now(),

    constraint ck_case_family_partition_class check (partition_class in
        ('TUNING','VALIDATION','HOLDOUT','REDTEAM'))
);

comment on table case_family_partition is
    'AM5 家族整组分区登记面（M5-02，insert-only）：同 scenario_family_id 二次登记'
    || '不同分区 = DuplicateKey 拒绝（落码方案 §M5-02② 分区兜底的可实施形态，C-6）';

-- ---------- 3. RLS + 四分区角色（V9 条件化建角色同构） ----------

do $$
begin
    if not exists (select from pg_roles where rolname = 'eval_tuning_ro') then
        create role eval_tuning_ro nologin;
    end if;
    if not exists (select from pg_roles where rolname = 'eval_validation_ro') then
        create role eval_validation_ro nologin;
    end if;
    if not exists (select from pg_roles where rolname = 'eval_holdout_gate') then
        create role eval_holdout_gate nologin;
    end if;
    if not exists (select from pg_roles where rolname = 'eval_redteam_gate') then
        create role eval_redteam_gate nologin;
    end if;
end
$$;

alter table case_version enable row level security;

-- 评分身份（eval_app / SCORING）：封存门前不见也不可写 HOLDOUT
create policy pol_case_version_eval_app_insert on case_version
    for insert to eval_app
    with check (partition_class <> 'HOLDOUT');
create policy pol_case_version_eval_app_select on case_version
    for select to eval_app
    using (partition_class <> 'HOLDOUT');

-- 四分区角色：各见单分区（只读）
create policy pol_case_version_eval_tuning_ro on case_version
    for select to eval_tuning_ro
    using (partition_class = 'TUNING');
create policy pol_case_version_eval_validation_ro on case_version
    for select to eval_validation_ro
    using (partition_class = 'VALIDATION');
create policy pol_case_version_eval_holdout_gate on case_version
    for select to eval_holdout_gate
    using (partition_class = 'HOLDOUT');
create policy pol_case_version_eval_redteam_gate on case_version
    for select to eval_redteam_gate
    using (partition_class = 'REDTEAM');

grant select on case_version to
    eval_tuning_ro, eval_validation_ro, eval_holdout_gate, eval_redteam_gate;

-- ---------- 4. INV-AM5-1 兜底：公共 benchmark 不冒充私有 HOLDOUT ----------

create function fn_assert_benchmark_not_holdout() returns trigger as $$
begin
    if new.source_class = 'PUBLIC_BENCHMARK' and new.partition_class = 'HOLDOUT' then
        raise exception 'INV-AM5-1: PUBLIC_BENCHMARK dataset % cannot claim HOLDOUT partition',
            new.name;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_dataset_benchmark_not_holdout
    before insert on dataset_version
    for each row execute function fn_assert_benchmark_not_holdout();

-- ---------- 5. 授权：登记面 eval_app 只读+插；生产角色零权限（V10 同构） ----------

grant select, insert on case_family_partition to eval_app;

revoke all on case_family_partition
    from control_app, publisher_app, notify_app, public;
-- arena 域角色在 control-only 干净库（IT 的 Testcontainers 库）可能不存在——
-- 条件化（幂等），V9/V10 同构
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on case_family_partition from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on case_family_partition from chaos_admin_app;
    end if;
end
$$;
