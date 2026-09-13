\echo ==RUN==
select id, state, engine, trigger_kind, generation from rca_run where id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
\echo ==CLAIMS==
select kind, status, lifecycle, jsonb_array_length(evidence_refs) as refs, sources, left(reason,160) from rca_claim where run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
\echo ==TOOLS==
select tool_name, state, count(*) from rca_tool_invocation where run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7' group by 1,2;
\echo ==REPORT==
select id, validation_status, model from rca_report where run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
\echo ==WINNER==
select incident_id, generation, winner_run_id, winner_report_id from report_generation_winner where winner_run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
\echo ==PUBLICATION==
select p.id, p.state, p.attempt_count from report_publication p join rca_report r on r.id=p.report_id where r.run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
\echo ==OUTBOX==
select o.id, o.state, o.channel, o.sent_at from notify_outbox o join rca_report r on r.id=o.report_id where r.run_id='8c0c30ef-9831-48ae-bb3a-0a8a78d2a9f7';
