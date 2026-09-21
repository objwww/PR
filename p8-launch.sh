#!/bin/sh
# p8 验证批：NATIVE 链 rca_tool_invocation 回退（p6-launch23 bcrypt 舞步套路）
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p8-launch-20260920')
BK=/tmp/env-backup-p8-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe-p8.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p8-launch-20260920" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P8-tool_invocation回退验证(全场景x1)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":1,"idempotencyKey":"p8-toolinv-verify-20260920"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 8
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '领取 LAUNCH|LAUNCH' | tail -2
