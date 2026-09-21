#!/bin/sh
# 波次3c：反馈插入 Instant 类型修复
set -e
cd /opt/build/pr
echo '33148e389865447c8ec725e978055b0e  /tmp/wave3c.tar.gz' | md5sum -c -
tar xzf /tmp/wave3c.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10
done
echo "health=$code"
