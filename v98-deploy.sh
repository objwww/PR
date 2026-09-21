#!/bin/sh
# V98 部署与 policy 资产核验
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep BUILD | tail -1
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app 2>&1 | tail -1
sleep 30
echo '=== 双 digest 锚（启动 log） ==='
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -E '上下文策略资产登记' | tail -1
echo '=== CONTEXT_POLICY 资产行 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select asset_kind || '|' || left(asset_digest,16) from release_asset where asset_kind='CONTEXT_POLICY';"
echo '=== flyway 98 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version='98';"
echo '=== ERROR count ==='
docker logs deploy-control-app-1 2>&1 | grep -c ERROR
exit 0
