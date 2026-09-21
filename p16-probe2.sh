#!/bin/sh
echo '=== 各 kind 计数 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select asset_kind, count(*) from release_asset group by asset_kind order by asset_kind;"
echo '=== PROMPT 资产 content 样例 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select asset_kind, left(asset_digest::text,8), content from release_asset where asset_kind='PROMPT' order by created_at desc limit 2;"
echo '=== 配置包指针 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select revision, left(bundle_digest::text,8), activated_at from config_bundle_pointer limit 1;"
echo '=== 配置包数量 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*), max(revision) from config_bundle;"
