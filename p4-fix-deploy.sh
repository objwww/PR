#!/bin/sh
set -e
cd /opt/build/pr
echo '93873baa5ceb2e8f74cf29354742b3d3  /tmp/p4-fix-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p4-fix-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker rm -f eval-worker-p3replay >/dev/null 2>&1 || true
sh /tmp/p2w.sh
