#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWTAG="p6$(date +%H%M%S)"
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std3.env
if grep -q '"run-tag":"[^"]*"' /tmp/std3.env; then
  sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/std3.env
else
  sed -i "s|\"app\":{\"alert\":{\"eval\":{|&\"run-tag\":\"$NEWTAG\",|" /tmp/std3.env
fi
echo "run-tag -> $(grep -o '"run-tag":"[^"]*"' /tmp/std3.env)"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std3.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication' | tail -2
echo '---S3/S4/S5 incidents---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key in ('alertname=ArenaDuplicateOrders|service=order-arena|job=order-arena','alertname=ArenaIllegalTransitions|service=order-arena|job=order-arena','alertname=ArenaOrderStuck|service=order-arena|job=order-arena');"
echo '---发起 run-20（同计划，供质量门配对）---'
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p4rt-20260917')
BK=/tmp/env-backup-p6r20-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe40.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p4rt-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P6-同计划复刻批(质量门配对基线)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","idempotencyKey":"p6-qualitygate-pair-20260919"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 6
docker logs eval-worker-std 2>&1 | grep -E '领取 LAUNCH' | tail -1
