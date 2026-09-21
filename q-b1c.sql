-- B1 真实入模验证 step3：run26 捕获面按 role × capture_level 概览
\timing off
\pset pager off

SELECT c.role_id, i.capture_level, count(*) AS rows,
       min(i.created_at) AS first_at, max(i.created_at) AS last_at,
       min(i.approx_tokens) AS min_tok, max(i.approx_tokens) AS max_tok,
       max(i.message_bytes) AS max_bytes
FROM rca_model_input i
JOIN rca_model_call c ON c.id = i.model_call_id
WHERE c.run_id = '55e575e5-bdac-4667-8947-9da821b26980'
GROUP BY c.role_id, i.capture_level
ORDER BY c.role_id, i.capture_level;

-- run26 主任务各轮 prompt 摘要（行数/长度/digest 去重）
SELECT count(*) AS prompts,
       count(DISTINCT i.prompt_digest) AS distinct_digests,
       sum(i.message_bytes) AS total_bytes
FROM rca_model_input i
JOIN rca_model_call c ON c.id = i.model_call_id
WHERE c.run_id = '55e575e5-bdac-4667-8947-9da821b26980'
  AND c.role_id LIKE '%primary%';
