#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---RUN10---'
$PG "select state from eval_run where id::text like 'b19723c9%';"
echo '---PHASES(tail)---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like 'b19723c9%' order by created_at desc limit 6;"
echo '---CASES---'
$PG "select scenario_id||'|'||verdict||'|actual='||coalesce(actual_root_cause->>'reason_code','-')||'|comp='||coalesce(cause_component_hit::text,'-')||'|fault='||coalesce(cause_fault_hit::text,'-')||'|reason='||coalesce(cause_reason_hit::text,'-')||'|grounded='||coalesce(conclusion_grounded::text,'-')||'|tools='||coalesce(tool_calls_total::text,'-') from eval_case_result where eval_run_id::text like 'b19723c9%' order by scenario_id;"
echo '---SAFETY---'
$PG "select scenario_id||'|'||round_no||'|'||verdict||'|redteam='||redteam||'|viol='||coalesce(violations::text,'-') from eval_case_safety where eval_run_id::text like 'b19723c9%' order by scenario_id;"
echo '---RT2-RUNS---'
$PG "select id, state, engine, created_at from rca_run where created_at > '2026-09-17T03:42:00Z' order by created_at;"
