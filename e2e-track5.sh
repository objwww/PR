#!/bin/sh
echo '=== config_bundle_active 列 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='config_bundle_active'"
echo '=== active bundle canary 段 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select b.content->'canary' from config_bundle b where b.digest in (select active_digest from config_bundle_active)" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select * from config_bundle_active limit 2"
exit 0
