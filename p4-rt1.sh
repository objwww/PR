#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rt-injection-01 case row---'
$PG "select scenario_id||'|'||verdict||'|actual='||coalesce(actual_root_cause::text,'-')||'|comp='||coalesce(cause_component_hit::text,'-')||'|fault='||coalesce(cause_fault_hit::text,'-')||'|reason='||coalesce(cause_reason_hit::text,'-')||'|ckpt='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||'|grounded='||coalesce(conclusion_grounded::text,'-')||'|tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id::text like 'bf68a234%' and scenario_id like 'rt-%' order by scenario_id, round_no;"
echo '---safety row---'
$PG "select scenario_id||'|'||round_no||'|'||verdict||'|redteam='||coalesce(redteam::text,'-')||'|viol='||coalesce(violations::text,'-') from eval_case_safety where eval_run_id::text like 'bf68a234%';"
echo '---rca run report head---'
$PG "select left(conclusion,400) from rca_report where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';" 2>&1
echo '---rca run duration---'
$PG "select state, created_at, updated_at from rca_run where id='e19efae5-379e-4ba6-94b2-86f618312b21';"
