-- RR04（OR-01）：新旧 Run 分别消费不同合法版本——各 Run 记录的实际身份不被当前 ACTIVE 覆盖
\timing off
\pset pager off

-- 1) 全部 run→skill 绑定（按时间），看历史身份多样性
SELECT b.run_id::text, b.role_id, b.selection_status, left(b.asset_digest,12) AS asset,
       left(b.release_digest,12) AS release, b.selector_version, b.created_at::time(0)
FROM rca_run_skill_binding b
ORDER BY b.created_at;

-- 2) 当前 ACTIVE 的 skill 资产（若存在 ACTIVE 面）
SELECT kind, left(digest,12) AS digest, state, created_at::time(0)
FROM release_asset
WHERE kind ILIKE '%skill%'
ORDER BY created_at DESC LIMIT 10;

-- 3) run26 的绑定行（重点核对）
SELECT b.run_id::text, b.role_id, b.selection_status, left(b.asset_digest,12) AS asset
FROM rca_run_skill_binding b
WHERE b.run_id = '55e575e5-bdac-4667-8947-9da821b26980';

-- 4) 各 run 的 release_digest（rca_run 面的实际消费身份）
SELECT r.id::text, left(r.release_digest,12) AS release, r.state, r.created_at::time(0)
FROM rca_run r
ORDER BY r.created_at DESC LIMIT 12;
