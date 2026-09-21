#!/bin/sh
# P6-G8 run-13 发起：panel=SMOKE（凭据换入式，anti-race 版——赛后才还原）
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p4rt-20260917')
BK=/tmp/env-backup-p6r13-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe34.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p4rt-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
echo '--- launch panel=SMOKE ---'
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P6-G8 SMOKE panel","mode":"L","datasetVersion":"rt-v3","panel":"SMOKE","idempotencyKey":"p6-g8-smoke-e2e-20260917"}' \
  -w '\nhttp=%{http_code}\n'
echo '--- 非法 panel 应被 gate 拒绝 ---'
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"panel 负例","mode":"L","datasetVersion":"rt-v3","panel":"NIGHTLY","idempotencyKey":"p6-g8-panel-neg-20260917"}' \
  -w '\nhttp=%{http_code}\n' | cut -c1-260
cp "$BK" .env
echo 'env-restored-file-only'
sleep 8
grep -E '领取 LAUNCH' /tmp/p4-worker.log | tail -1
