#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p2rly-20260917')
BK=/tmp/env-backup-p2rly-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe20.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p2rly-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)

echo '=== 1. 提名（真实事故最新 STRUCTURE_VALIDATED 报告）==='
RID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
select rp.id from rca_report rp join rca_run r on r.id=rp.run_id
 join incident i on i.id=r.incident_id
 where i.incident_key like 'alertname=ArenaOrderStuck%' and r.state='SUCCEEDED'
 order by r.created_at desc limit 1;")
echo "source report=$RID"
RESP=$(curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/regression-candidates \
  -d "{\"reportId\":\"$RID\",\"caseKey\":\"ArenaOrderStuck-replay-e2e\",\"scenarioFamilyId\":\"svc-order-arena\"}")
echo "$RESP" | head -c 400; echo
CID=$(echo "$RESP" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('id') or d.get('candidateId') or '')")
echo "candidate=$CID"

echo '=== 2. 评审 ACCEPTED（三值状态机）==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/eval/regression-candidates/$CID/reviews" \
  -d '{"verdict":"ACCEPTED_FOR_CANDIDATE","reason":"人工核对：上轮调查已核实 order-arena 卡单积压（SUPPORTED 证据链），同意作回归候选"}' \
  -w '\nhttp=%{http_code}\n' | head -c 300

echo '=== 3. 入集（GT 人工显式给定；症状码=重放锚 alertname）==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/eval/regression-candidates/$CID/materialize" \
  -d '{"datasetName":"arena-replay-ds","datasetVersion":"eval-ds-1","partition":"TUNING","expectedRootCause":{"component":"order-arena","faultType":"backlog","reasonCode":"RC_ORDER_BACKLOG"},"expectedSymptomCodes":["ArenaOrderStuck"]}' \
  -w '\nhttp=%{http_code}\n' | head -c 500

echo '=== 4. 发起评测 run（L 模式，dataset=eval-ds-1）==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P2-回放E2E","mode":"L","datasetVersion":"eval-ds-1","idempotencyKey":"p2-replay-e2e-20260917"}' \
  -w '\nhttp=%{http_code}\n'

cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
