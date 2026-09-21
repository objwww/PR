#!/bin/sh
# 第 12 轮部署：监控环比双窗（perf-trend）+ 口令轮换 + 烟测
set -e
PW='Tmp#p319-0917'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p319-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p319-batch.tar -C /opt/build/pr
echo '=== 1) 源码在场 ==='
grep -c 'perf-trend' control-app/src/main/java/com/objwww/pr/control/ops/interfaces/AgentOpsController.java
grep -c '性能概览' alert-web/src/views/MonitorView.vue
echo '=== 2) mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
echo '=== 3) docker build ==='
cd deploy
docker compose build control-app web 2>&1 | tail -1
echo '=== 4) 镜像 ID 与时间 ==='
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Id}} {{.Created}}'
echo '=== 5) up -d ==='
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
SBK=/tmp/env-backup-p319shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p319shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p319.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p319-0917'
echo '=== 烟测：perf-trend 双窗 ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/agent-ops/perf-trend"
echo '=== 回归：costs / risk-events ==='
curl -s -o /dev/null -w 'costs=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
curl -s -o /dev/null -w 'risk=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/risk-events"
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
