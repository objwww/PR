#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=480dfe13
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---phases(tail5)---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at desc limit 5;"
echo '---cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|d='||coalesce(difficulty,'-')||'|hit='||root_cause_hit||'|actual='||coalesce(actual_root_cause->>'reason_code','-')||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---rca runs---'
$PG "select id, state, engine, created_at from rca_run where created_at > '2026-09-18T17:51:00Z' order by created_at limit 6;"
echo '---judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%';"
