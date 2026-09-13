\echo ==RUN==
select id, state, engine, completion_kind, trigger_kind from rca_run where id='d122eb0b-af2b-4068-9428-b60b9356aa2a';
\echo ==TASKS==
select task_type, task_key, state from rca_task where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a' order by created_at;
\echo ==TOOLCALLS==
select tool_name, state, reason_code, count(*) from rca_tool_invocation where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a' group by 1,2,3 order by 1;
\echo ==EVIDENCE==
select evidence_type, source, count(*) from rca_evidence where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a' group by 1,2;
\echo ==CLAIMS==
select kind, status, lifecycle, coalesce(array_length(evidence_refs,1),0) as refs, sources from rca_claim where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a';
\echo ==MODELCALLS==
select requested_model, state, usage, cost_micros, currency from rca_model_call where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a' order by created_at;
\echo ==REPORT==
select id, validation_status, model, total_tokens from rca_report where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a';
\echo ==PUBLICATION==
select p.id, p.state, p.attempt_count from report_publication p join rca_report r on r.id=p.report_id where r.run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a';
\echo ==OUTBOX==
select id, state, channel, sent_at from notify_outbox where report_id in (select id from rca_report where run_id='d122eb0b-af2b-4068-9428-b60b9356aa2a');
