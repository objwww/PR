SELECT tool_name, state, reason_code, result_ref IS NOT NULL AS has_ref
FROM rca_tool_invocation
WHERE run_id='654c1bef-87e9-4a27-ac2c-73b145dc0fdf' ORDER BY call_seq;
SELECT kind, status, lifecycle, jsonb_array_length(evidence_refs) AS refs
FROM rca_claim WHERE run_id='654c1bef-87e9-4a27-ac2c-73b145dc0fdf';
