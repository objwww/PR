#!/bin/sh
# V107 RLS 插入策略应用 + smoke 重跑
cd /opt/build/pr
tar -xzf /opt/build/op-fix3.tar.gz -C /opt/build/pr
cd deploy
docker compose up migrate 2>&1 | tail -2
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version in ('106','107') order by version::int;"
echo V107-APPLY-DONE
