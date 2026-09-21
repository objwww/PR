#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p3e2e-20260917')
BK=/tmp/env-backup-p3e2e-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe25.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p3e2e-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)

echo '=== 1. 提名（P3 带检查点案例）==='
RESP=$(curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/regression-candidates \
  -d '{"reportId":"dff51130-f8b9-455c-a20f-3370cf9f050c","caseKey":"ArenaOrderStuck-replay-p3","scenarioFamilyId":"svc-order-arena"}')
CID=$(echo "$RESP" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('id') or d.get('candidateId') or '')")
echo "candidate=$CID"

echo '=== 2. 评审 ACCEPTED ==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/eval/regression-candidates/$CID/reviews" \
  -d '{"verdict":"ACCEPTED_FOR_CANDIDATE","reason":"人工核对：同源事故回归锚，P3 三维评分检查点入集"}' \
  -o /dev/null -w 'review=%{http_code}\n'

echo '=== 3. 入集（GT + 证据检查点）==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/eval/regression-candidates/$CID/materialize" \
  -d '{"datasetName":"arena-replay-ds","datasetVersion":"eval-ds-1","partition":"TUNING","expectedRootCause":{"component":"order-arena","faultType":"backlog","reasonCode":"RC_ORDER_BACKLOG"},"expectedSymptomCodes":["ArenaOrderStuck"],"evidenceCheckpoints":["ArenaOrderStuck","oa_stuck_orders","checkout"]}' \
  -w '\nhttp=%{http_code}\n' | head -c 400

echo '=== 4. 发起 run-6 ==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P3-三维评分E2E","mode":"L","datasetVersion":"eval-ds-1","idempotencyKey":"p3-dims-e2e-20260917"}' \
  -w '\nhttp=%{http_code}\n'

cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
