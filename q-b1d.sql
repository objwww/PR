-- B1 真实入模验证 step4：5 个 primary prompt 的嵌套证据标记定位
\timing off
\pset pager off

SELECT c.action_seq, c.round_id, i.approx_tokens AS toks, i.message_bytes AS bytes,
       position('PlaceOrder' in i.prompt_text) AS pos_placeorder,
       position('rpc_client' in i.prompt_text) AS pos_rpc,
       position('error_type' in i.prompt_text) AS pos_errtype,
       position('UNKNOWN' in i.prompt_text) AS pos_unknown,
       position('logs.query' in i.prompt_text) AS pos_logsquery,
       position('valid_artifact_refs' in i.prompt_text) AS pos_envelope,
       position('truncated' in i.prompt_text) AS pos_trunc,
       position('observations' in i.prompt_text) AS pos_obs
FROM rca_model_input i
JOIN rca_model_call c ON c.id = i.model_call_id
WHERE c.run_id = '55e575e5-bdac-4667-8947-9da821b26980'
  AND c.role_id = 'primary'
ORDER BY c.action_seq;
