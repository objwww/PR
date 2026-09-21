#!/bin/sh
set -e
cd /opt/build/pr/deploy
echo "control-app 状态: $(docker ps --format '{{.Names}} {{.Status}}' | grep deploy-control-app)"
echo "当前 .env 口令行前 20 字符: $(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env | head -c 40)..."
echo "备份链: $(cat /tmp/p316shot-bk-path)"
PW='Tmp#p316-0917'
SBK=/tmp/env-backup-p316re-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p316shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
grep -c "$ESC_HASH" .env || echo 'sed 未命中！'
docker compose up -d control-app >/dev/null 2>&1
sleep 35
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 5; i=$((i+1))
done
echo "health=$code"
J=/tmp/p316.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p316-0917'
echo '=== costs（修复后） ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
curl -s -o /dev/null -w 'latency=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/latency-layers"
curl -s -o /dev/null -w 'risk=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/risk-events"
