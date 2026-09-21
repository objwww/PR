#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---worker---'
docker ps --format '{{.Names}} {{.Status}}' | grep eval
echo '---RUNNING---'
$PG "select left(id::text,8) from eval_run where state='RUNNING' and display_name is not null and display_name != '';"
echo '---FIRING---'
$PG "select incident_key from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---ACTIVE chaos---'
$PG "select scenario_id||'|'||state from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING');"
echo '---disk---'
df -h / | tail -1
