#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- decision 值域:'
P "select decision, count(*) from canary_route_decision group by 1 order by 2 desc;"
echo '--- 每服务最新放量:'
P "select distinct on (split_part(stickiness_key, '|', 2)) split_part(stickiness_key, '|', 2) as service, percent, decision, created_at from canary_route_decision order by split_part(stickiness_key, '|', 2), created_at desc limit 8;"
