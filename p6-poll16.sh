#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=09d4d3eb
sleep 520
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|tools='||coalesce(tool_calls_total::text,'-')||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---judge---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||passed||'/'||total||'|'||coalesce(left(error,80),'ok') from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
