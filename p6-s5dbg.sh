#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---S5/ArenaOrderStuck incidents 01:10-01:40---'
$PG "select incident_key, status, generation, last_event_at from incident where incident_key like 'alertname=ArenaOrderStuck%' and last_event_at > '2026-09-19T01:10:00Z' order by last_event_at desc limit 4;"
echo '---rca runs 01:10-01:40---'
$PG "select id, state, created_at, finished_at from rca_run where created_at > '2026-09-19T01:10:00Z' and created_at < '2026-09-19T01:40:00Z' order by created_at;"
echo '---cf1ac104 S5 failure sample---'
$PG "select failure_sample::text from eval_case_result where eval_run_id::text like 'cf1ac104%' and scenario_id='S5';"
echo '---chaos sessions tag p6091111---'
$PG "select scenario_id||'|'||state||'|gen='||generation from arena.oa_chaos_session where scenario_id like 'chaos-eval-p6091111%';"
