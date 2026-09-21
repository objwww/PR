#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 480
echo '---state---'
$PG "select state from eval_run where id::text like '5dc7b905%';"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|tools='||coalesce(tool_calls_total::text,'-')||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '5dc7b905%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total||'|'||coalesce(left(error,60),'ok') from eval_case_judge where eval_run_id::text like '5dc7b905%';"
