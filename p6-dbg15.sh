#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---failure samples---'
$PG "select scenario_id||'|r'||round_no||'|'||left(failure_sample::text,150) from eval_case_result where eval_run_id::text like '5dc7b905%' order by scenario_id, round_no;"
echo '---who claimed---'
sed 's/\x1b\[[0-9;]*m//g' /tmp/p6-worker.log | grep -E '领取 LAUNCH|拒绝' | tail -2 | cut -c1-200
echo '---rca runs since launch---'
$PG "select count(*) from rca_run where created_at > '2026-09-18T18:03:00Z';"
echo '---incidents touched---'
$PG "select incident_key, status, last_event_at from incident where last_event_at > '2026-09-18T18:03:00Z' order by last_event_at desc limit 6;"
