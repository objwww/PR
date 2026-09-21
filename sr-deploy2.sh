#!/bin/sh
# SR 批第二次：锁序修复重部署 + PostgresRunReconcilerIT 真跑
set -x
cd /opt/build/pr
tar -xzf /opt/build/pr-sr.tar.gz -C /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep BUILD | tail -1
cd deploy
docker compose build control-app web 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -1
sleep 30
echo '=== health ==='
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '=== ERROR count ==='
docker logs deploy-control-app-1 2>&1 | grep -c ERROR
echo '=== SR IT 真跑 ==='
cd /opt/build/pr
mvn -B -ntp -pl control-app test -Dtest=PostgresRunReconcilerIT -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:|BUILD' | tail -5
exit 0
