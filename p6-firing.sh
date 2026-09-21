#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---firing no-job incident labels---'
$PG "select common_labels::text, alert_count from incident where incident_key='alertname=ArenaDuplicateOrders|service=order-arena' and status='FIRING';"
echo '---fingerprint---'
$PG "select fingerprint from incident where incident_key='alertname=ArenaDuplicateOrders|service=order-arena' and status='FIRING';"
