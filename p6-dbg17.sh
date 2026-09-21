#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---failure samples---'
$PG "select scenario_id||'|r'||round_no||'|'||left(failure_sample::text,200) from eval_case_result where eval_run_id::text like 'c056492a%' order by scenario_id, round_no;"
