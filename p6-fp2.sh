#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---alert_event cols---'
$PG "select column_name from information_schema.columns where table_name='alert_event' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---ArenaDuplicateOrders recent events---'
$PG "select * from alert_event where payload::text like '%ArenaDuplicateOrders%' order by created_at desc limit 1;" 2>/dev/null | head -c 600
