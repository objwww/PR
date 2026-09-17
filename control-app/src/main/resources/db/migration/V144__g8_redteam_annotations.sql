-- ============================================================================
-- V144 —— P6-G8 红队案例难度/panel 标注：redteam-ds:rt-v3 新版本
--
-- case_version 的 uq(dataset_version_id, case_key) 冻结"同案例单行"——案例内容
-- 版本化 = 新数据集版本（V141→V142 同律，首版误按适用期换行被约束正确拒绝）。
-- 本迁移：登记 rt-v3 版本头 + 五案例标注行（payload 承自 rt-v2 + rawArtifact
-- 保留键 gt_difficulty/gt_panel），rt-v2 行按 valid_to 收口退役。
-- 标注：五案例 gt_difficulty=L2（单诱饵单症状-需跨证据定位）；rt-injection-03
-- （k8s 写操作探针）gt_panel=true 入 SMOKE 快刷子集——panel E2E 真验以
-- "5 案例筛选出 1"为判据（forPanel 过滤 + 难度落档双证明）。
-- ============================================================================

-- ---------- 1. rt-v3 版本头 ----------
insert into dataset_version (
    id, source, name, version, source_uri, license, access_class,
    content_digest, adapter_version, imported_at, source_class, partition_class,
    scenario_family_digest
) values (
    '7a1b0000-0000-4000-8000-000000000003',
    'redteam-crafted', 'redteam-ds', 'rt-v3',
    'seed:v144', 'internal', 'INTERNAL',
    encode(sha256(convert_to('redteam-ds:rt-v3:v144', 'UTF8')), 'hex'),
    'redteam-seed.v3', now(), 'PRIVATE', 'REDTEAM',
    encode(sha256(convert_to('redteam-injection-probe', 'UTF8')), 'hex')
)
on conflict do nothing;

-- ---------- 2. 收口 rt-v2 旧行 ----------
update case_version
   set valid_to = now()
 where dataset_version_id = '7a1b0000-0000-4000-8000-000000000002'
   and valid_to is null;

-- ---------- 3. 五案例标注行（payload 承自 rt-v2 + 保留键） ----------
insert into case_version (
    id, dataset_version_id, case_key, scenario_family_id,
    valid_from, valid_to, content_digest, payload,
    source_artifact_ref, partition_class
)
select
    ('7a1b0000-0000-4000-8000-00000000004' || nr)::uuid,
    '7a1b0000-0000-4000-8000-000000000003',
    v.case_key,
    v.scenario_family_id,
    now(), null,
    encode(sha256(convert_to(v.payload::text, 'UTF8')), 'hex'),
    jsonb_set(
        jsonb_set(v.payload,
            '{rawArtifact,gt_difficulty}', to_jsonb(v.difficulty)),
        '{rawArtifact,gt_panel}',
        to_jsonb(case when v.panel then 'true' else 'false' end)),
    'seed:v144-redteam', 'REDTEAM'
from (
    select cv.case_key, cv.scenario_family_id, cv.payload,
           'L2' as difficulty,
           (cv.case_key = 'rt-injection-03') as panel,
           row_number() over (order by cv.case_key) as nr
      from case_version cv
     where cv.dataset_version_id = '7a1b0000-0000-4000-8000-000000000002'
       and cv.valid_to is not null
) v;
