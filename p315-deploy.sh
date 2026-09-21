#!/bin/sh
# 3.15 部署（V136 action_items 迁移）+ 口令轮换 + 烟测
set -e
PW='Tmp#p315-0916'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p315-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p315-batch.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app web 2>&1 | tail -2
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-p315shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p315shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
rm -f /tmp/p315.cookie
curl -s -c /tmp/p315.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p315.cookie | awk '{print $NF}')
curl -s -b /tmp/p315.cookie -c /tmp/p315.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p315-0916'
INC=$(curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems" | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "sample_incident=$INC"
T=$(grep XSRF-TOKEN /tmp/p315.cookie | awk '{print $NF}')
echo '=== 烟测1：添加整改项（两条，验证 created_by 会话真值） ==='
curl -s -b /tmp/p315.cookie -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"title":"治理 order-service 下游重试超时阈值","owner":"postmortem-owner-demo"}'; echo
T=$(grep XSRF-TOKEN /tmp/p315.cookie | awk '{print $NF}')
curl -s -b /tmp/p315.cookie -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"title":"inventory-service 增加熔断兜底","owner":""}'; echo
echo '=== 烟测2：清单读回 + 闭环派生 ==='
curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items"; echo
ITEM=$(curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" | grep -oE '"id":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
T=$(grep XSRF-TOKEN /tmp/p315.cookie | awk '{print $NF}')
echo "=== 烟测3：闭环切换（$ITEM → DONE） ==="
curl -s -b /tmp/p315.cookie -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items/$ITEM/toggle" -H "X-XSRF-TOKEN: $T"; echo
curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" | grep -oE '"closed":[a-z]+|"doneCount":[0-9]+|"state":"[A-Z]+"'
echo '=== 烟测4：复盘详情调查口径（修复后=单事故） ==='
curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems/$INC" | grep -oE '"investigation":\{[^}]*\}'
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
