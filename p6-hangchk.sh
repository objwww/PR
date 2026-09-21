#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=e007de1c
echo '---current time---'
date -u +%FT%TZ
echo '---phase---'
$PG "select phase, created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at desc limit 1;"
echo '---scoring elapsed---'
$PG "select extract(epoch from now() - (select created_at from eval_phase_event where eval_run_id::text like '$RUN%' and phase='SCORING' order by created_at desc limit 1))::int as scoring_elapsed_s;"
echo '---rca runs---'
$PG "select left(id::text,8)||' '||state||' '||created_at from rca_run where created_at > '2026-09-19T21:26:00Z' order by created_at;"
echo '---firing---'
$PG "select incident_key from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---active chaos---'
$PG "select scenario_id||'|'||fault_type||'|'||state from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING');"
