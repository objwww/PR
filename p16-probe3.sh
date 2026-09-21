#!/bin/sh
echo '=== 激活指针表名与当前指针 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name from information_schema.tables where table_name like 'config_bundle%';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select * from config_bundle_activation limit 1;" 2>/dev/null || true
echo '=== 最新 bundle 的 content 键结构（截断） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(content::text, 600) from config_bundle where revision=151;"
echo '=== bundle content 是否含 prompt digest 引用 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from config_bundle where content::text like '%b3fa6124%';"
