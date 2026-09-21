#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---incident cols---'
$PG "select column_name from information_schema.columns where table_name='incident' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---ArenaOrderStuck incidents---'
$PG "select id, status, opened_at, closed_at from incident where alert_name='ArenaOrderStuck' order by opened_at desc limit 5;" 2>&1
echo '---rca_run for that inbox row (any run since 22:00)---'
$PG "select id, state, created_at from rca_run where created_at > '2026-09-16T22:00:00Z' order by created_at desc limit 10;" 2>&1
