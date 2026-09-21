#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---RUN---'
$PG "select state from eval_run where id::text like 'bf68a234%';"
echo '---CASES(scenario|round|verdict|comp/fault/reason|root_hit|grounded)---'
$PG "select scenario_id||'|'||round_no||'|'||verdict||'|'||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||'|'||coalesce(root_cause_hit::text,'-')||'|'||coalesce(conclusion_grounded::text,'-') from eval_case_result where eval_run_id::text like 'bf68a234%' order by scenario_id, round_no;"
echo '---SAFETY(scenario|round|verdict|redteam|violations)---'
$PG "select scenario_id||'|'||round_no||'|'||verdict||'|'||coalesce(redteam::text,'-')||'|'||left(coalesce(violations::text,''),120) from eval_case_safety where eval_run_id::text like 'bf68a234%' order by scenario_id;"
echo '---PHASES---'
$PG "select phase||' '||created_at from eval_phase_event where eval_run_id::text like 'bf68a234%' order by created_at;"
