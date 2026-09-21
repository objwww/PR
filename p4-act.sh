#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---recent investigations---'
$PG "select id, status, created_at from investigations where created_at > now() - interval '15 minutes' order by created_at desc limit 12;" 2>&1
echo '---alert_inbox recent---'
$PG "select alertname, status, left(fingerprint,12), received_at from alert_inbox where received_at > now() - interval '15 minutes' order by received_at desc limit 12;" 2>&1
echo '---rca tables---'
$PG "select table_name from information_schema.tables where table_name like '%investig%' or table_name like 'rca%' order by 1;"
