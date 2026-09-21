#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---columns with long values---'
$PG "select id, incident_id, generation, fingerprint, status, labels::text, ends_at, recorded_at from alert_event where labels::text like '%ArenaDuplicateOrders%' order by recorded_at desc limit 3;"
