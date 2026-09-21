#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 240
echo '---phases tail---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like 'db5b9826%' order by created_at desc limit 5;"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||coalesce(left(failure_sample::text,120),'ok') from eval_case_result where eval_run_id::text like 'db5b9826%' order by scenario_id, round_no;"
