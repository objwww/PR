-- B1 真实入模验证 step5：确认 prompt_text 是否为空 + digest 完整性
\timing off
\pset pager off

SELECT c.action_seq, i.capture_level,
       i.prompt_digest IS NOT NULL AS has_digest,
       i.prompt_text IS NULL AS text_is_null,
       length(i.prompt_text) AS text_len,
       i.message_bytes, i.approx_tokens, i.redaction_note
FROM rca_model_input i
JOIN rca_model_call c ON c.id = i.model_call_id
WHERE c.run_id = '55e575e5-bdac-4667-8947-9da821b26980'
  AND c.role_id = 'primary'
ORDER BY c.action_seq;

-- capture_level 的全库取值域（确认是否有 FULL 级的先例）
SELECT capture_level, count(*) FROM rca_model_input GROUP BY capture_level;
