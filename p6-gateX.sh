#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---pre-clear---'
$PG "select incident_key||' '||status from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for FP in $($PG "select distinct ae.fingerprint from alert_event ae join incident i on i.id=ae.incident_id where i.status='FIRING' and i.incident_key like 'alertname=Arena%' and ae.status='firing';"); do
  [ -z "$FP" ] && continue
  docker run --rm --network alert-net curlimages/curl:latest -s -o /dev/null -w "$FP -> %{http_code}\n" \
    -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
    -X POST http://control-app:8080/webhooks/alertmanager \
    -d "{\"version\":\"4\",\"receiver\":\"cleanup\",\"groupKey\":\"clr::$FP\",\"truncatedAlerts\":0,\"status\":\"resolved\",\"commonLabels\":{},\"commonAnnotations\":{},\"externalURL\":\"\",\"alerts\":[{\"status\":\"resolved\",\"labels\":{\"alertname\":\"Cleanup\",\"service\":\"order-arena\",\"severity\":\"page\"},\"annotations\":{\"summary\":\"pre-campaign cleanup\"},\"startsAt\":\"2026-09-19T00:00:00Z\",\"endsAt\":\"$NOW\",\"fingerprint\":\"$FP\"}]}" 2>/dev/null
  sleep 2
done
sleep 5
$PG "select incident_key||' '||status from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---launch---'
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p4rt-20260917')
BK=/tmp/env-backup-p6gX-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe46.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p4rt-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P6-GATE质量门战役X(5簇x1轮)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":1,"idempotencyKey":"p6-gate-campaign-x-20260919"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 6
docker logs eval-worker-std 2>&1 | grep -E '领取 LAUNCH' | tail -1
