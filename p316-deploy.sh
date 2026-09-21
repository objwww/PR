#!/bin/sh
# 3.10 Wave4 监控三区块部署（零迁移）+ 口令轮换 + 烟测
set -e
PW='Tmp#p316-0917'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p316-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p316-batch.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app web 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -1
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-p316shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p316shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p316.cookie
mint() { curl -s -b $J -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf; grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}'; }
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p316-0917'
echo '=== 烟测1：分层延迟（四层，任务层为本批新增） ==='
curl -s -b $J "http://127.0.0.1:8080/api/agent-ops/latency-layers"; echo
echo '=== 烟测2：成本归因（按模型定价回算） ==='
curl -s -b $J "http://127.0.0.1:8080/api/agent-ops/costs"; echo
echo '=== 烟测3：风险审计流（Guardian/审批拒绝/隔离死信三源） ==='
curl -s -b $J "http://127.0.0.1:8080/api/agent-ops/risk-events" | head -c 900; echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
