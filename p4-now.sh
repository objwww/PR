#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---server now---'; date -u +%FT%TZ
echo '---run state---'
$PG "select state from eval_run where id::text like 'bf68a234%';"
echo '---phases---'
$PG "select phase, detail, created_at from eval_phase_event where eval_run_id::text like 'bf68a234%' order by created_at;"
echo '---inbox rows last 90 min---'
$PG "select state, decision, alert_count, received_at, processed_at from alert_inbox where received_at > now() - interval '90 minutes' order by received_at;"
echo '---rca_run last 90 min---'
$PG "select id, state, created_at from rca_run where created_at > now() - interval '90 minutes' order by created_at;"
echo '---cases---'
$PG "select scenario_id, round_no, verdict from eval_case_result where eval_run_id::text like 'bf68a234%' order by scenario_id, round_no;"
