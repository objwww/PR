#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---failure samples---'
$PG "select scenario_id||'|r'||round_no||'|'||left(failure_sample::text,170) from eval_case_result where eval_run_id::text like '09d4d3eb%' order by scenario_id, round_no;"
echo '---phases---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '09d4d3eb%' order by created_at limit 14;"
echo '---incidents touched since 19:32---'
$PG "select incident_key, status, last_event_at from incident where last_event_at > '2026-09-18T19:32:00Z' order by last_event_at limit 8;"
echo '---rca runs since---'
$PG "select count(*) from rca_run where created_at > '2026-09-18T19:32:00Z';"
