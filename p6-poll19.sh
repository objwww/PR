#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=cd682643
sleep 420
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|tools='||coalesce(tool_calls_total::text,'-')||'|lat='||coalesce(latency_ms::text,'-')||'|'||coalesce(left(failure_sample::text,60),'ok') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---judge---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||passed||'/'||total||'|'||coalesce(left(error,80),'ok') from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---phases tail---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at desc limit 4;"
