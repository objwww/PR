#!/bin/sh
set -e
cd /opt/build/pr
echo 'c7fa99e63472c80580058ce9f2b0920a  /tmp/p3-settle-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p3-settle-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- run-6 是否已终态（等它自然收官再换 worker）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
