#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- canary_route_decision 列:'
P "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns where table_name='canary_route_decision';"
echo '--- canary_route_decision 样例 3 条:'
P "select * from canary_route_decision order by id desc limit 3;" 2>/dev/null | head -5
echo '--- 行数:'
P "select count(*) from canary_route_decision;"
