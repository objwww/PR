#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo '---firing fingerprints---'
$PG "select ae.fingerprint, i.incident_key from alert_event ae join incident i on i.id=ae.incident_id where i.status='FIRING' and i.incident_key like 'alertname=Arena%' and ae.status='firing' order by ae.recorded_at desc limit 4;" > /tmp/fp.out 2>&1
cat /tmp/fp.out
while read FP IKEY; do
  AN=$(echo "$IKEY" | sed -E 's/alertname=([^|]+).*/\1/')
  SVC=$(echo "$IKEY" | sed -E 's/.*service=([^|]+).*/\1/')
  FT=$(echo "$IKEY" | grep -o 'fault_type=[^|]*' | cut -d= -f2)
  echo "resolve $AN ($FP) ft=$FT"
  docker run --rm --network alert-net curlimages/curl:latest -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
    -X POST http://control-app:8080/webhooks/alertmanager \
    -d "{\"version\":\"4\",\"receiver\":\"oncall\",\"groupKey\":\"cleanup::$FP\",\"truncatedAlerts\":0,\"status\":\"resolved\",\"commonLabels\":{},\"commonAnnotations\":{},\"externalURL\":\"\",\"alerts\":[{\"status\":\"resolved\",\"labels\":{\"alertname\":\"$AN\",\"service\":\"$SVC\",\"severity\":\"page\"},\"annotations\":{\"summary\":\"gate cleanup\"},\"startsAt\":\"2026-09-19T08:00:00Z\",\"endsAt\":\"$NOW\",\"fingerprint\":\"$FP\"}]}"
  sleep 3
done < /tmp/fp.out
echo '---final state---'
$PG "select incident_key||' '||status from incident where incident_key like 'alertname=Arena%' and status='FIRING';"
