#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---phases---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '47a01647%' order by created_at;"
echo '---inbox since 06:25---'
$PG "select decision, received_at from alert_inbox where received_at > '2026-09-17T06:25:00Z' order by received_at;"
echo '---PodCrashLoopBackOff incident---'
$PG "select incident_key, status, waiting_reason from incident where incident_key like '%PodCrashLoopBackOff%';"
echo '---worker log tail---'
grep -E 'ERROR|WARN|失败' /tmp/p4-worker.log | tail -6 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-200
