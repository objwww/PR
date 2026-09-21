#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=cf1ac104
sleep 500
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---comparison gate (FUP-03 验证点)---'
$PG "select gate_outcome||' | '||gate_reasons::text||' | paired='||paired_count||' missing='||coalesce((select count(*) from jsonb_array_elements(gate_reasons) g) ,-1) from eval_comparison where candidate_run_id::text like '$RUN%';" 2>&1 | head -4
