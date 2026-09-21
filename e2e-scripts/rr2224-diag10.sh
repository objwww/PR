#!/bin/sh
# 决定性取证 v2：真列名 canary_bucket/bundle_digest
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===RECENT-DECISIONS-FULL===
$PG "select id, decision, canary_bucket, percent, bundle_digest, created_at from canary_route_decision where id in (26, 30, 483, 496) order by id"
echo ===ACTIVE-POINTER===
$PG "select * from config_bundle_active"
echo ===CONFIG-BUNDLE-COLS===
$PG "select column_name from information_schema.columns where table_name='config_bundle' order by ordinal_position"
