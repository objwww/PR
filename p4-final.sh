#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---run-9 terminal_reason---'
$PG "select terminal_reason from eval_run where id::text like 'bf68a234%';"
echo '---worker log exception hunt---'
grep -n -B2 -A12 'ERROR\|Exception' /tmp/p2-worker.log | sed 's/\x1b\[[0-9;]*m//g' | tail -40
echo '---worker container alive?---'
docker ps --format '{{.Names}}' | grep -c eval-worker || true
