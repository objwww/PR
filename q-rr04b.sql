-- RR04 补充：release 资产状态面 + rca_run.config_digest（各 run 实际消费身份）
\timing off
\pset pager off

-- 1) release_asset：各版本资产的 state（ACTIVE/RETIRED…），看多版本共存
SELECT asset_kind, left(asset_digest,12) AS digest, state, created_at::time(0)
FROM release_asset
ORDER BY created_at DESC LIMIT 15;

-- 2) 各 run 实际消费的 config_digest（引擎身份面，应各不相同且不被当前值覆盖）
SELECT left(config_digest,12) AS config, state, created_at::time(0)
FROM rca_run
ORDER BY created_at DESC LIMIT 12;

-- 3) 两个停滞 REPORTING run 的身份（对照组：老 run 的老身份仍在）
SELECT left(id::text,8) AS run8, left(config_digest,12) AS config, state, created_at::time(0), reconcile_deadline_at::time(0) AS rdeadline
FROM rca_run
WHERE state IN ('REPORTING','QUEUED','RUNNING')
ORDER BY created_at;
