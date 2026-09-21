#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rca_run recent---'
$PG "select id, status, created_at from rca_run where created_at > now() - interval '20 minutes' order by created_at desc limit 12;"
echo '---alert_inbox recent---'
$PG "select id, payload::text like '%rt-%' as is_rt, received_at from alert_inbox where received_at > now() - interval '20 minutes' order by received_at desc limit 12;" 2>&1
echo '---alert_inbox cols---'
$PG "select column_name from information_schema.columns where table_name='alert_inbox' order by ordinal_position;"
