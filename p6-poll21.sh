#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=12bcfacc
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---judge---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---comparison of 12bcfacc---'
$PG "select left(baseline_run_id::text,8)||' vs cand | '||gate_outcome||' | reasons='||gate_reasons::text from eval_comparison where candidate_run_id::text like '$RUN%';"
