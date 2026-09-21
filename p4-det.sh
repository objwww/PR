#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---run-11 failure samples---'
$PG "select scenario_id||' | '||left(failure_sample::text,220) from eval_case_result where eval_run_id::text like 'cb88a559%' order by scenario_id;"
