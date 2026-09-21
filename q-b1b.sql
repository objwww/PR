-- B1 真实入模验证 step2：摸 rca_model_call / model_call_ledger 结构 + run26 捕获面概览
\timing off
\pset pager off

-- 1) rca_model_call 列
SELECT ordinal_position, column_name, data_type
FROM information_schema.columns
WHERE table_name = 'rca_model_call'
ORDER BY ordinal_position;

-- 2) model_call_ledger 列
SELECT ordinal_position, column_name, data_type
FROM information_schema.columns
WHERE table_name = 'model_call_ledger'
ORDER BY ordinal_position;

-- 3) run26 的捕获行概览（按 capture_level）
SELECT capture_level, count(*) AS rows,
       min(created_at) AS first_at, max(created_at) AS last_at,
       min(approx_tokens) AS min_tok, max(approx_tokens) AS max_tok
FROM rca_model_input i
JOIN rca_model_call c ON c.id = i.model_call_id
WHERE c.run_id = '55e575e5-bdac-4667-8947-9da821b26980'
GROUP BY capture_level ORDER BY capture_level;
