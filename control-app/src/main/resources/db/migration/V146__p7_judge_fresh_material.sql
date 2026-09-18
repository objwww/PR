-- ============================================================================
-- V146 —— P6-G7 judge E2E 新材料：redteam-ds:rt-v4（新指纹=新材料）
--
-- 材料=全 payload 摘要（含 fingerprint）——V144 沿用 rt2-XX 指纹，run-12 已把
-- rt-v3 五案例全部测量（材料耗尽，单次测量边界）。judge 真验需要全新调查，
-- 本迁移登记 rt-v4：payload 承自 rt-v3 行，fingerprint rt2-XX→rt3-XX、
-- groupKey rt2::→rt3:: 换新；gt_difficulty=L2 全保留、gt_panel=true 全量
-- （SMOKE panel 一次跑全五例，panel 过滤+judge 双证明一趟完成）。
-- ============================================================================

insert into dataset_version (
    id, source, name, version, source_uri, license, access_class,
    content_digest, adapter_version, imported_at, source_class, partition_class,
    scenario_family_digest
) values (
    '7a1b0000-0000-4000-8000-000000000004',
    'redteam-crafted', 'redteam-ds', 'rt-v4',
    'seed:v146', 'internal', 'INTERNAL',
    encode(sha256(convert_to('redteam-ds:rt-v4:v146', 'UTF8')), 'hex'),
    'redteam-seed.v4', now(), 'PRIVATE', 'REDTEAM',
    encode(sha256(convert_to('redteam-injection-probe', 'UTF8')), 'hex')
)
on conflict do nothing;

update case_version
   set valid_to = now()
 where dataset_version_id = '7a1b0000-0000-4000-8000-000000000003'
   and valid_to is null;

insert into case_version (
    id, dataset_version_id, case_key, scenario_family_id,
    valid_from, valid_to, content_digest, payload,
    source_artifact_ref, partition_class
)
select
    ('7a1b0000-0000-4000-8000-00000000005' || nr)::uuid,
    '7a1b0000-0000-4000-8000-000000000004',
    v.case_key,
    v.scenario_family_id,
    now(), null,
    encode(sha256(convert_to(v.payload::text, 'UTF8')), 'hex'),
    jsonb_set(
        jsonb_set(
            jsonb_set(v.payload,
                '{rawArtifact,gt_panel}', to_jsonb('true'::text)),
            '{rawArtifact,adversarial_payload_json}',
            to_jsonb(replace(replace(
                v.payload#>>'{rawArtifact,adversarial_payload_json}',
                'rt2::', 'rt3::'), '"fingerprint": "rt2-', '"fingerprint": "rt3-'))),
        '{rawArtifact,gt_difficulty}', to_jsonb(v.difficulty)),
    'seed:v146-redteam', 'REDTEAM'
from (
    select cv.case_key, cv.scenario_family_id, cv.payload,
           cv.payload#>>'{rawArtifact,gt_difficulty}' as difficulty,
           row_number() over (order by cv.case_key) as nr
      from case_version cv
     where cv.dataset_version_id = '7a1b0000-0000-4000-8000-000000000003'
       and cv.valid_to is not null
) v;
