#!/bin/sh
# 3.4 补强部署：诊断评价采集（V137）+ 口令轮换 + 烟测（七步产物验尸）
set -e
PW='Tmp#p318-0917'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p318-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p318-batch.tar -C /opt/build/pr
echo '=== 1) 源码在场核验 ==='
grep -c 'diag_session_feedback' control-app/src/main/resources/db/migration/V137__diag_session_feedback.sql control-app/src/main/java/com/objwww/pr/control/alert/interfaces/DiagSessionController.java | tr '\n' ' '; echo
grep -c '有用' alert-web/src/views/DiagChatView.vue
echo '=== 2) mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
echo '=== 3) target jar class 验尸 ==='
/opt/jdk-21.0.12.1+1/bin/jar tf control-app/target/control-app-0.0.1-SNAPSHOT.jar | grep -c 'DiagStatsController.class'
echo '=== 4) docker build ==='
cd deploy
docker compose build control-app web 2>&1 | tail -1
echo '=== 5) 迁移 + 镜像 ID ==='
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Id}} {{.Created}}'
echo '=== 6) up -d ==='
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
echo '=== 口令轮换 ==='
SBK=/tmp/env-backup-p318shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p318shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p318.cookie
mint() { curl -s -b $J -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf; grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}'; }
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p318-0917'
INC=$(curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents?limit=1" | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
echo '=== 烟测1：发起快捷问拿 session_id ==='
T=$(mint)
RES=$(curl -s -X POST "http://127.0.0.1:8080/api/v1/incidents/$INC/diag" -b $J -c $J -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"key":"timeline","createdBy":"human:operator"}')
echo "$RES" | head -c 200; echo
SID=$(echo "$RES" | grep -oE '"session_id":"[0-9a-f-]+"' | cut -d'"' -f4)
echo "SID=$SID"
echo '=== 烟测2：评 UP ==='
T=$(mint)
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$INC/diag/feedback" -b $J -c $J -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\",\"rating\":\"UP\",\"createdBy\":\"human:operator\"}"
echo '=== 烟测3：改评 DOWN+原因（upsert 覆盖验证） ==='
T=$(mint)
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$INC/diag/feedback" -b $J -c $J -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SID\",\"rating\":\"DOWN\",\"reason\":\"答非所问（验收演示）\",\"createdBy\":\"human:operator\"}"
echo '=== 烟测4：history 带出评价态 ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/diag" | grep -oE '"id":"[0-9a-f-]{36}","question_key":"timeline"[^}]*' | head -c 400; echo
echo '=== 烟测5：stats 好评分布 ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/diag/stats"; echo
echo '=== SQL 对账 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select rating, count(*), coalesce(string_agg(reason, ' | '), '—') from diag_session_feedback group by rating order by rating;"
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
