#!/bin/sh
# 关联调查兜底修复部署（PostgresIncidentQueryReader 最近 run 回落）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/5] md5 对拍 ==='
echo '4ed3d2bd00c0a708f0a46a2c4ddb7227  /tmp/linkfix.tar.gz' | md5sum -c -
tar xzf /tmp/linkfix.tar.gz
echo '=== [2/5] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [3/5] compose build + up ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose up -d control-app 2>&1 | tail -2
echo '=== [4/5] health ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
echo '=== [5/5] 启动核验 ==='
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
