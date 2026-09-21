#!/bin/sh
set -e
cd /opt/build/pr
echo '0331fda7c83e064804d36d0feabdf72e  /tmp/p4-batch2d.tar.gz' | md5sum -c -
tar xzf /tmp/p4-batch2d.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
