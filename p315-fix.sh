#!/bin/sh
# 3.15 修正补部署：state 值域对齐（SUCCESS/FAILED 真值）+ 合入并行会话 latestReportId
set -e
J=/tmp/p315.cookie
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p315-fix.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p315-fix.tar -C /opt/build/pr
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
INC=f4cf44b2-a5fd-48fe-875c-69ba3f45d78a
echo '=== 验证1：调查口径（成功/失败真值归类） ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC" | grep -oE '"investigation":\{[^}]*\}|"latestReportId":"[0-9a-f-]+"'
echo '=== 验证2：整改项清单仍在 ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" | grep -oE '"count":[0-9]+|"doneCount":[0-9]+|"closed":[a-z]+'
