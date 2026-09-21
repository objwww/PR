SELECT evidence_type, payload::jsonb->'data'->'result' AS result
FROM rca_evidence
WHERE run_id='654c1bef-87e9-4a27-ac2c-73b145dc0fdf'
  AND evidence_type='logs.aggregate';
