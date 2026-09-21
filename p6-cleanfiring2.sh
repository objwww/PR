#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo '---distinct firing fingerprints---'
$PG "select distinct ae.fingerprint, i.incident_key from alert_event ae join incident i on i.id=ae.incident_id where i.status='FIRING' and i.incident_key like 'alertname=Arena%';"
echo '---resolve each---'
$PG "select distinct ae.fingerprint from alert_event ae join incident i on i.id=ae.incident_id where i.status='FIRING' and i.incident_key like 'alertname=Arena%';" | while read FP; do
  [ -z "$FP" ] && continue
  docker run --rm --network alert-net curlimages/curl:latest -s -o /dev/null -w "$FP -> %{http_code}\n" \
    -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
    -X POST http://control-app:8080/webhooks/alertmanager \
    -d "{\"version\":\"4\",\"receiver\":\"oncall\",\"groupKey\":\"c::$FP\",\"truncatedAlerts\":0,\"status\":\"resolved\",\"commonLabels\":{},\"commonAnnotations\":{},\"externalURL\":\"\",\"alerts\":[{\"status\":\"resolved\",\"labels\":{\"alertname\":\"ArenaOrderStuck\",\"service\":\"order-arena\",\"severity\":\"page\"},\"annotations\":{\"summary\":\"cleanup\"},\"startsAt\":\"2026-09-19T08:00:00Z\",\"endsAt\":\"$NOW\",\"fingerprint\":\"$FP\"}]}"
done
sleep 5
$PG "select incident_key||' '||status from incident where incident_key like 'alertname=Arena%' and status='FIRING';"
