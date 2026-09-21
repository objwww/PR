SELECT evidence_type,
       left((payload::jsonb->'data'->'result')::text, 340) AS head
FROM rca_evidence
WHERE run_id='55e575e5-bdac-4667-8947-9da821b26980'
ORDER BY evidence_type;
