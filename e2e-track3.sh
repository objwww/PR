#!/bin/sh
echo '=== 既往 canary 决策（最近 8） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select stickiness_key || ' | bucket=' || coalesce(bucket::text,'-') || ' | ' || decision || ' | run=' || coalesce(run_id::text,'-') || ' | ' || created_at from canary_decision order by created_at desc limit 8" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name from information_schema.tables where table_name like '%canary%'"
echo '=== config bundle canary 段 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select digest || ' | ' || created_at from config_bundle order by created_at desc limit 3" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name from information_schema.tables where table_name like '%bundle%'"
exit 0
