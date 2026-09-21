#!/bin/sh
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===INCIDENT-COLS===
$PG "select column_name from information_schema.columns where table_name='incident' order by ordinal_position"
echo ===EVENT-COLS===
$PG "select column_name from information_schema.columns where table_name='alert_event' order by ordinal_position"
