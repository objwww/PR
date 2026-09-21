SELECT tool_name, state, reason_code, result_ref IS NOT NULL AS has_ref
FROM rca_tool_invocation
WHERE run_id='55e575e5-bdac-4667-8947-9da821b26980' ORDER BY call_seq;
SELECT kind, status, lifecycle, evidence_basis, jsonb_array_length(evidence_refs) AS refs,
       left(reason, 700) AS reason
FROM rca_claim WHERE run_id='55e575e5-bdac-4667-8947-9da821b26980';
SELECT evidence_type, left(payload::jsonb->'data'->'result'::text, 300) AS head
FROM rca_evidence WHERE run_id='55e575e5-bdac-4667-8947-9da821b26980';
