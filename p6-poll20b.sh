#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=bea12f45
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-')||'|'||coalesce(left(failure_sample::text,80),'ok') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---judge---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---comparison vs cd682643---'
$PG "select gate_outcome, gate_reasons::text, paired_count, unpaired_count from eval_comparison where candidate_run_id::text like '$RUN%';"
