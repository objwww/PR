#!/bin/sh
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p321-fix.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p321-fix.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -1
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 5; i=$((i+1))
done
echo "health=$code"
J=/tmp/p321.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Demo#0917'
curl -s -b $J 'http://127.0.0.1:8080/api/v1/routing/overview' | head -c 450
echo
