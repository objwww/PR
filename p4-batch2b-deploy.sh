#!/bin/sh
set -e
cd /opt/build/pr
echo '2b6f9b944d07a8ff4d0708fbdf5a985d  /tmp/p4-batch2b.tar.gz' | md5sum -c -
tar xzf /tmp/p4-batch2b.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
BK=/tmp/env-bk-dsver-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
if grep -q '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env; then
  sed -i 's|^APP_EVAL_LAUNCH_DATASET_VERSIONS=.*|APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2|' .env
else
  echo 'APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2' >> .env
fi
grep '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
sh /tmp/p4r10.sh
