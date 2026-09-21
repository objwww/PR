#!/bin/sh
echo '---control-app log since 03:42 (rca/budget/reconcile)---'
docker logs deploy-control-app-1 --since '2026-09-17T03:42:00Z' 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-240 | grep -iE 'rca|budget|reconcil|investig|projector|claim' | tail -25
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rca_claim recent---'
$PG "select count(*) from rca_claim where created_at > '2026-09-17T03:40:00Z';" 2>&1
echo '---rca_task recent---'
$PG "select count(*) from rca_task where created_at > '2026-09-17T03:40:00Z';" 2>&1
echo '---incident current_rca_run---'
$PG "select incident_key, current_rca_run_id, waiting_reason from incident where incident_key like 'alertname=PaymentChargeFailure%';"
