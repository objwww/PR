#!/bin/sh
echo '=== PROMPT 资产样例（content jsonb 原文） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select kind, left(digest::text,8), content from release_asset where kind='PROMPT' order by created_at desc limit 2;" 2>/dev/null | head -10
echo '=== 各 kind 计数 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select kind, count(*) from release_asset group by kind order by kind;"
echo '=== release_asset 列 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='release_asset' order by ordinal_position;"
echo '=== 当前激活配置包 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select revision, left(bundle_digest::text,8), activated_at from config_bundle_pointer limit 1;" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name like 'config_bundle%' order by table_name, ordinal_position limit 20;"
