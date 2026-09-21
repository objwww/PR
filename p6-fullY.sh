#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=e007de1c
echo '---now---'
date -u +%FT%TZ
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---all cases---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|hit='||root_cause_hit||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---all judge---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id;"
echo '---all phases---'
$PG "select phase||' '||coalesce(detail::text,'')||' '||created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at;"
echo '---comparison---'
$PG "select gate_outcome||' | '||gate_reasons::text||' | paired='||paired_count from eval_comparison where candidate_run_id::text like '$RUN%' or baseline_run_id::text like '$RUN%';"
echo '---rca runs for this window---'
$PG "select left(id::text,8)||' '||state||' '||created_at from rca_run where created_at > '2026-09-19T21:25:00Z' order by created_at;"
