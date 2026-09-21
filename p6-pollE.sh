#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 420
echo '---eccb56f2---'
$PG "select state from eval_run where id::text like 'eccb56f2%';"
echo '---cases---'
$PG "select scenario_id||'|'||verdict||'|hit='||root_cause_hit||'|'||coalesce(left(failure_sample::text,90),'ok') from eval_case_result where eval_run_id::text like 'eccb56f2%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like 'eccb56f2%' order by scenario_id;"
