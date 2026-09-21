#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=c312e4b1
sleep 540
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|'||verdict||'|hit='||root_cause_hit||'|tool='||coalesce(tool_calls_total::text,'-')||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---metrics---'
$PG "select 'hit_rate='||round(end_to_end_hit_rate::numeric*100,1)||'% F1='||coalesce(round(f1_score::numeric*100,1),'-')||'% coverage='||round(coverage::numeric*100,1)||'%' from eval_run where id::text like '$RUN%';" 2>&1
