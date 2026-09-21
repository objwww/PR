#!/bin/sh
# run-4 发起（凭据换验）+ 60s 后看激活结果
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p2r4-20260917')
BK=/tmp/env-backup-p2r4-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe23.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p2r4-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P2-回放E2E-4","mode":"L","datasetVersion":"eval-ds-1","idempotencyKey":"p2-replay-e2e-20260917-4"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
sleep 50
echo '=== 60s 后：worker 激活结果 ==='
grep -E '注入失败|REPLAYING|AWAITING' /tmp/p2-worker.log | tail -4
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' inbox, latest '||max(received_at)::text from alert_inbox where convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state from eval_run order by started_at desc limit 1;"
