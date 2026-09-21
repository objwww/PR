#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
$PG "select incident_key, status, generation, fingerprint_hash from incident where incident_key='alertname=ArenaDuplicateOrders|service=order-arena' and status='FIRING';" 2>/dev/null || $PG "select column_name from information_schema.columns where table_name='incident' and (column_name like '%finger%' or column_name like '%generation%');"
