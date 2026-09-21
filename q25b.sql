SELECT evidence_type, left(payload::jsonb->'data'->'result'::text, 260) AS result_head
FROM rca_evidence WHERE run_id='654c1bef-87e9-4a27-ac2c-73b145dc0fdf';
SELECT kind, status, evidence_basis, left(reason, 600) AS reason
FROM rca_claim WHERE run_id='654c1bef-87e9-4a27-ac2c-73b145dc0fdf';
