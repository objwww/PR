#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== config_bundles 列 ==='
Q "select column_name||':'||data_type from information_schema.columns where table_name='config_bundles' order by ordinal_position" | tr '\n' ' '
echo
echo '=== 行数与最新3条 ==='
Q "select count(*) from config_bundles"
Q "select left(digest::text,12)||' | '||kind||' | '||coalesce(version,'-')||' | '||created_at from config_bundles order by created_at desc limit 3" 2>/dev/null || true
echo '=== assets 表? ==='
Q "select table_name from information_schema.tables where table_name like '%asset%' or table_name like '%bundle%'"
