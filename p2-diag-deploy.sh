#!/bin/sh
set -e
cd /opt/build/pr
echo 'a47d1131fc4a076ba26f5667f982e758  /tmp/p2-diag-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p2-diag-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
# 重启 worker（换新镜像）
docker rm -f eval-worker-p2replay >/dev/null 2>&1 || true
sh /tmp/p2w.sh
