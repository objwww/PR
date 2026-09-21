#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p2cxl-20260917')
BK=/tmp/env-backup-p2cxl-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe21.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p2cxl-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
echo '=== 取消被陈旧 worker 抢跑的 run ==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs/eed0e10d-e020-4f3c-abbc-161cbc166d1a/cancel \
  -w '\nhttp=%{http_code}\n'
echo '=== 发起干净的新 run ==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P2-回放E2E-2","mode":"L","datasetVersion":"eval-ds-1","idempotencyKey":"p2-replay-e2e-20260917-2"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '=== 两个 run 状态 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||display_name||' | '||state from eval_run order by started_at desc limit 2;"
