#!/bin/sh
set -e
cd /opt/build/pr
echo 'f27314aca70e0abeb7905135e1acb3e7  /tmp/p5-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p5-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app web 2>&1 | grep -E 'DONE|ERROR' | tail -4
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- P5 endpoint 对账 ---'
TOK=$(grep '^APP_OPERATOR_API_BEARER=' .env | cut -d= -f2- | tr -d '\r"')
curl -s -H "Authorization: Bearer $TOK" http://127.0.0.1:8080/api/v1/analytics/trends | cut -c1-1200
