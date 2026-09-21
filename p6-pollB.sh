#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=eccb56f2
sleep 540
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-')||'|'||coalesce(left(failure_sample::text,90),'ok') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id;"
