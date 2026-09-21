#!/bin/sh
echo '--- flyway history 概览（近 15 条 + 缺口检查）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success||'|'||coalesce(description,'-') from flyway_schema_history order by installed_rank desc limit 15;"
echo '--- 64/87/140/141 是否在册 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version in ('64','87','140','141');"
echo '--- 迁移文件目录的 V141/V87 是否在场 ---'
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | grep -E 'V87|V141' || true
