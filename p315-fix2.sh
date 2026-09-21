#!/bin/sh
# 3.15 二次补部署：itsm/tickets 坏 SQL 修复（复盘页工单草稿清单 403）
set -e
J=/tmp/p315.cookie
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p315-fix2.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p315-fix2.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p315-0916'
echo '=== 验证：itsm/tickets（修复后应 200 且 count 落真值） ==='
curl -s -o /dev/null -w 'tickets=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/v1/itsm/tickets"
curl -s -b $J "http://127.0.0.1:8080/api/v1/itsm/tickets" | head -c 300; echo
