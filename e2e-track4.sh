#!/bin/sh
echo '=== canary_route_decision 列 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='canary_route_decision' order by ordinal_position"
echo '=== 最近 8 决策 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select decision || ' | key=' || coalesce(stickiness_key,'-') || ' | run=' || coalesce(run_id::text,'-') || ' | ' || created_at from canary_route_decision order by created_at desc limit 8"
echo '=== active bundle canary 段 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select b.content->'canary' from config_bundle b join config_bundle_active a on a.digest=b.digest"
exit 0
