#!/bin/sh
set -e
cd /opt/build/pr
echo 'd01196498d207b6f033f10c9b965b2bb  /tmp/p3-roundfix-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p3-roundfix-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- run-7 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='f87b17a5-19b8-4159-9cd7-b492509f0d70';"
