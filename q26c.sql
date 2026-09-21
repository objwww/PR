SELECT p.id, left(p.report_id::text, 8) AS report, p.state, p.attempt_count,
       p.last_error, p.created_at
FROM report_publication p ORDER BY p.created_at DESC LIMIT 5;
SELECT count(*) AS total FROM report_publication;
