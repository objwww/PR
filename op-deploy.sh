#!/bin/sh
# OP 批（OP-01~05）部署：V99~V105 迁移 + control-app/web 重建
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
echo '=== mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -5
cd deploy
echo '=== compose build ==='
docker compose build control-app web 2>&1 | tail -2
echo '=== migrate ==='
docker compose up migrate 2>&1 | tail -3
echo '=== flyway 99-105 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version in ('99','100','101','102','103','104','105') order by version::int;"
echo '=== up -d ==='
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
echo '=== health ==='
curl -s -o /dev/null -w 'control=%{http_code}\n' http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w 'web=%{http_code}\n' http://127.0.0.1:8090/
echo '=== started/error ==='
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'Started ControlApplication'
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c ERROR
echo OP-DEPLOY-DONE
