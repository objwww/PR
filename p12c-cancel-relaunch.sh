#!/bin/sh
# p12c: 取消卡死的 B1（不带 panel 误发全量）→ 重发正确口径 B1'（panel=SMOKE ×2 轮）
# 口令已持久化（Demo#0917），不再走 bcrypt 舞步
set -e
cd /opt/build/pr/deploy
J=/tmp/probe-p12c.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -o /dev/null -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Demo#0917" \
  -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
echo '--- cancel B1 ---'
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/eval/runs/91aa342a-0332-4df4-ae7f-7ad9e05118ca/cancel" \
  -d '{"idempotencyKey":"p12c-cancel-b1-20260920","reason":"误发不带panel=全量25场景口径，卡在flagd场景首案例；质量门口径应为panel SMOKE 5场景x2轮"}' \
  -w '\nhttp=%{http_code}\n'
sleep 6
echo '--- relaunch B1prime (panel=SMOKE rounds=2) ---'
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P12c-质量门双批B1p(SMOKE5场景x2轮)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":2,"idempotencyKey":"p12c-gate-b1p-20260920"}' \
  -w '\nhttp=%{http_code}\n'
sleep 8
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '领取 LAUNCH' | tail -2
