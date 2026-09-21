#!/bin/sh
# OP 修复重部署：仅 control-app（parseId 判空修复）
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD' | tail -1
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 30
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker logs deploy-control-app-1 --since 2m 2>&1 | grep -c 'Started ControlApplication'
docker logs deploy-control-app-1 --since 2m 2>&1 | grep -c ERROR
echo OP-REDEPLOY-DONE
