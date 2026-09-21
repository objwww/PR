#!/bin/sh
set -e
cd /opt/build/pr
tar xzf /tmp/diag1.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep BUILD | tail -1
cd deploy
docker compose up migrate 2>&1 | tail -1
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'FLYWAY='||version from flyway_schema_history where success order by installed_rank desc limit 1"
