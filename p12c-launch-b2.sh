#!/bin/sh
# p12c 简化版 B2 发批（口令已持久化，直接重启 control-app 载入后登录）
set -e
cd /opt/build/pr/deploy
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
J=/tmp/probe-p12b2.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -o /dev/null -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Demo#0917" \
  -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P12c-质量门双批B2(SMOKE5场景x2轮)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":2,"idempotencyKey":"p12c-gate-b2-20260920"}' \
  -w '\nhttp=%{http_code}\n'
sleep 8
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '领取 LAUNCH' | tail -1
echo B2_LAUNCHED
