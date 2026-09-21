#!/bin/sh
# 波次3部署（调查证据链内联+结构化驳回+复核待办+重查入口）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/6] md5 对拍 ==='
echo '4318d2d57cf8dfc46d59530eab32ad5b  /tmp/wave3.tar.gz' | md5sum -c -
tar xzf /tmp/wave3.tar.gz
echo '=== [2/6] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [3/6] compose build ==='
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose build web 2>&1 | tail -1
echo '=== [4/6] migrate + up ==='
docker compose up migrate 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -2
echo '=== [5/6] health ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
echo '=== [6/6] 核验 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
