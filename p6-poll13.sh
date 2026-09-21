#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 150
echo '---run13---'
$PG "select state from eval_run where id::text like '47a01647%';"
echo '---cases---'
$PG "select scenario_id||'|'||round_no||'|'||verdict||'|diff='||coalesce(difficulty,'-')||'|actual='||coalesce(actual_root_cause->>'reason_code','-') from eval_case_result where eval_run_id::text like '47a01647%';"
echo '---safety---'
$PG "select scenario_id||'|'||verdict||'|redteam='||redteam from eval_case_safety where eval_run_id::text like '47a01647%';"
echo '---new runs---'
$PG "select id, state, engine, created_at from rca_run where created_at > '2026-09-17T06:25:00Z';"
