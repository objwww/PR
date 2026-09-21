#!/bin/sh
set -e
cd /opt/build/pr
echo 'c954ab2f45bf757d40235bb0a99a2ab8  /tmp/full-src.tar.gz' | md5sum -c -
tar xzf /tmp/full-src.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|cannot find symbol' | head -4
cd deploy
docker compose build control-app web 2>&1 | grep -cE 'DONE'
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- 重启 worker（命令行参数全量配置版）---'
docker rm -f eval-worker-p6real >/dev/null 2>&1 || true
sh /tmp/p6wf.sh
