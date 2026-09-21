#!/bin/sh
echo '=== drill 相关表 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name from information_schema.tables where table_name like 'drill%' order by 1;"
echo '=== drill_job 列 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='drill_job' order by ordinal_position;"
echo '=== 演练行数与样例 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from drill_job;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8), status, created_at::date from drill_job order by created_at desc limit 5;"
echo '=== fault_mode 是否已存在 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from information_schema.tables where table_name='fault_mode';"
