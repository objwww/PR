#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---phase detail---'
$PG "select phase, detail from eval_phase_event where eval_run_id::text like 'bf68a234%' order by created_at;"
echo '---control-app webhook log 01:27-01:30---'
docker logs deploy-control-app-1 --since '2026-09-17T01:27:00Z' --until '2026-09-17T01:31:00Z' 2>&1 | grep -iv 'actuator\|health' | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-200 | grep -i 'webhook\|inbox\|alert\|bearer\|auth\|secur' | tail -30
echo '---inbox rows last 40 min count---'
$PG "select count(*), min(received_at), max(received_at) from alert_inbox where received_at > now() - interval '40 minutes';"
