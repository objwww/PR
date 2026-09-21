#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---no-job incident alert events---'
$PG "select id, incident_id, generation, fingerprint, status, labels::text, recorded_at from alert_event where labels::text like '%ArenaDuplicateOrders%' and labels::text not like '%job%' order by recorded_at desc limit 3;"
echo '---no-job incident id---'
$PG "select id, incident_key, status, generation from incident where incident_key='alertname=ArenaDuplicateOrders|service=order-arena';"
