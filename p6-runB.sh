#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWTAG="p6B$(date +%H%M%S)"
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/stdB.env
if grep -q '"run-tag":"[^"]*"' /tmp/stdB.env; then
  sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/stdB.env
else
  sed -i "s|{\"app\":{\"alert\":{\"eval\":{|&\"run-tag\":\"$NEWTAG\",|" /tmp/stdB.env
fi
echo "run-tag -> $(grep -o '\"run-tag\":\"[^\"]*\"' /tmp/stdB.env)"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/stdB.env \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication' | tail -2
echo '---pre-clear---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---launch B---'
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p4rt-20260917')
BK=/tmp/env-backup-p6gB-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe48.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p4rt-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P6-GATE质量门战役B(5簇x1轮)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":1,"idempotencyKey":"p6-gate-campaign-b-20260920"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 6
docker logs eval-worker-std 2>&1 | grep -E '领取 LAUNCH' | tail -1
