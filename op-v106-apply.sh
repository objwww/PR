#!/bin/sh
# V106 授权迁移应用 + 校验
cd /opt/build/pr/deploy
docker compose up migrate 2>&1 | tail -3
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version='106';"
echo V106-APPLY-DONE
