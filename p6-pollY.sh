#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=e007de1c
sleep 560
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-')||'|'||coalesce(left(failure_sample::text,80),'ok') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---completion---'
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '批件完成' | tail -1
